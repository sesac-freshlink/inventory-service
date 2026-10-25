package com.freshlink.inventory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.freshlink.common.error.BusinessException;
import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.DisposeMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.common.message.result.ReserveFailReason;
import com.freshlink.common.message.result.ReserveFailedMessage;
import com.freshlink.common.message.result.ReservedMessage;
import com.freshlink.inventory.common.messaging.InventoryCommandListener;
import com.freshlink.inventory.domain.disposal.DisposalRequest;
import com.freshlink.inventory.domain.disposal.DisposalResponse;
import com.freshlink.inventory.domain.disposal.DisposalService;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실패 시나리오(T-23) 중 PC에서 자동으로 확인할 수 있는 것. DLQ 재전송처럼 dev에서만 되는 것은
 * docs/실패-시나리오-절차.md에 절차로 적는다. 지금 시각은 2026-10-25 12:00 KST로 고정한다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class FailureScenarioTest {

    static final String FRESH = "2027-12-31T23:59:59+09:00";
    static final String IMMINENT = "2026-10-25T23:59:59+09:00";

    @Autowired
    InventoryCommandService inventoryCommandService;

    @Autowired
    InventoryCommandListener inventoryCommandListener;

    @Autowired
    DisposalService disposalService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    Logger serviceLogger;
    ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void attachLogAppender() {
        serviceLogger = (Logger) LoggerFactory.getLogger(InventoryCommandService.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        serviceLogger.detachAppender(logs);
    }

    @Test
    void confirmAfterPaymentFailedReleaseKeepsStockAndLogsOneError() {
        long storeId = 2301L;
        long productId = insertProduct("P2301");
        long lotId = inbound(storeId, "P2301", 10, FRESH);
        handle(reserve(2301L, storeId, productId, 2));
        handle(new ReleaseMessage(newId(), NOW, 2301L, storeId, ReleaseReason.PAYMENT_FAILED));

        handle(new ConfirmMessage(newId(), NOW, 2301L, storeId));

        assertThat(lot(lotId)).containsEntry("quantity", 10).containsEntry("reserved_quantity", 0);
        assertThat(ledgerCount(lotId, "SALE")).isZero();
        assertThat(errorLogs()).hasSize(1);
    }

    @Test
    void stockReleasedByExpiredOrderIsReservedByNewOrder() {
        long storeId = 2302L;
        long productId = insertProduct("P2302");
        long lotId = inbound(storeId, "P2302", 3, FRESH);
        handle(reserve(2302L, storeId, productId, 3));
        handle(new ReleaseMessage(newId(), NOW, 2302L, storeId, ReleaseReason.EXPIRED));

        handle(reserve(2303L, storeId, productId, 3));

        assertThat(lot(lotId)).containsEntry("quantity", 3).containsEntry("reserved_quantity", 3);
        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM reservation WHERE order_id = ?", String.class, 2303L))
                .containsExactly("RESERVED");
        ReservedMessage reserved = (ReservedMessage) MessageJson.fromJson(
                (String) outboxRows(2303L).getFirst().get("payload"));
        assertThat(reserved.quantity()).isEqualTo(3);
        assertThat(reserved.lots()).hasSize(1);
    }

    @Test
    void inboundWithUnknownProductCodeThrowsAndRollsBack() {
        InboundMessage message = new InboundMessage(newId(), NOW, 2304L, "SHP-T23-2304",
                List.of(new InboundItem("NO-SUCH-CODE", 10, OffsetDateTime.parse(FRESH))));

        assertThatThrownBy(() -> handle(message))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("PRODUCT_NOT_FOUND"));

        assertThat(count("SELECT COUNT(*) FROM processed_message WHERE message_id = ?", message.messageId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM inbound WHERE shipment_id = ?", message.shipmentId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM inventory_lot WHERE store_id = ?", 2304L)).isZero();
    }

    @Test
    void sameInboundResentWithNewMessageIdIsAppliedOnce() {
        insertProduct("P2305");
        List<InboundItem> items = List.of(new InboundItem("P2305", 10, OffsetDateTime.parse(FRESH)));

        handle(new InboundMessage(newId(), NOW, 2305L, "SHP-T23-2305", items));
        handle(new InboundMessage(newId(), NOW, 2305L, "SHP-T23-2305", items));

        assertThat(count("SELECT SUM(quantity) FROM inventory_lot WHERE store_id = ?", 2305L)).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM inbound WHERE shipment_id = ?", "SHP-T23-2305")).isEqualTo(1);
    }

    @Test
    void disposeWithShortStockAtProcessingIsSkipped() {
        long storeId = 2306L;
        long productId = insertProduct("P2306");
        long lotId = inbound(storeId, "P2306", 5, IMMINENT);
        DisposalResponse disposal = disposalService.request(storeId, "owner-sub-2306", new DisposalRequest(lotId, 5));
        handle(reserve(2306L, storeId, productId, 1));

        handle(DisposeMessage.of(disposal.disposalId(), storeId, lotId, 5, "owner-sub-2306"));

        assertThat(lot(lotId)).containsEntry("quantity", 5).containsEntry("reserved_quantity", 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM disposal WHERE id = ?", String.class, disposal.disposalId()))
                .isEqualTo("SKIPPED");
        assertThat(ledgerCount(lotId, "DISPOSE")).isZero();
    }

    @Test
    void unknownTypeJsonThrowsInParserAndListener() {
        String body = """
                {"messageId":"%s","type":"UNKNOWN","createdAt":"2026-10-25T12:00:00+09:00","storeId":2307}
                """.formatted(newId());

        assertThatThrownBy(() -> MessageJson.fromJson(body))
                .isInstanceOf(JacksonException.class);
        // 리스너도 JSON 예외를 삼키지 않고 그대로 던진다. 재전송·DLQ 이동은 SQS 설정 몫이라 이 테스트 밖이다(절차 문서 4장)
        assertThatThrownBy(() -> inventoryCommandListener.onCommand(body))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void reserveWithZeroQuantitySendsInvalidQuantity() {
        long storeId = 2308L;
        long productId = insertProduct("P2308");
        long lotId = inbound(storeId, "P2308", 5, FRESH);

        handle(reserve(2308L, storeId, productId, 0));

        List<Map<String, Object>> outbox = outboxRows(2308L);
        assertThat(outbox).hasSize(1);
        assertThat(outbox.getFirst().get("message_type")).isEqualTo("RESERVE_FAILED");
        ReserveFailedMessage failed = (ReserveFailedMessage) MessageJson.fromJson(
                (String) outbox.getFirst().get("payload"));
        assertThat(failed.reason()).isEqualTo(ReserveFailReason.INVALID_QUANTITY);
        assertThat(lot(lotId)).containsEntry("reserved_quantity", 0);
    }

    private void handle(BaseMessage message) {
        inventoryCommandService.handle(message);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private long insertProduct(String productCode) {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, "실패 시나리오 " + productCode, "테스트", 1500L);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    /** INBOUND로 lot 하나를 입고하고 lot ID를 돌려준다 */
    private long inbound(long storeId, String productCode, int quantity, String expiryDate) {
        handle(new InboundMessage(newId(), NOW, storeId, "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(expiryDate)))));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_lot WHERE store_id = ?", Long.class, storeId);
    }

    private ReserveMessage reserve(long orderId, long storeId, long productId, int quantity) {
        return new ReserveMessage(newId(), NOW, orderId, storeId, productId, quantity);
    }

    private Map<String, Object> lot(long lotId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT quantity, reserved_quantity FROM inventory_lot WHERE id = ?", lotId);
        return Map.of(
                "quantity", ((Number) row.get("quantity")).intValue(),
                "reserved_quantity", ((Number) row.get("reserved_quantity")).intValue()
        );
    }

    private int ledgerCount(long lotId, String type) {
        return count("SELECT COUNT(*) FROM ledger WHERE lot_id = ? AND type = ?", lotId, type);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private List<Map<String, Object>> outboxRows(long orderId) {
        return jdbcTemplate.queryForList("""
                SELECT message_type, payload
                FROM outbox
                WHERE JSON_UNQUOTE(JSON_EXTRACT(payload, '$.orderId')) = ?
                ORDER BY id
                """, String.valueOf(orderId));
    }

    private List<ILoggingEvent> errorLogs() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
    }
}
