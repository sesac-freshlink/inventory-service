package com.freshlink.inventory;

import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.common.message.result.ReserveFailReason;
import com.freshlink.common.message.result.ReserveFailedMessage;
import com.freshlink.common.message.result.ReservedLot;
import com.freshlink.common.message.result.ReservedMessage;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * RESERVE 처리(명세 5-3). 지금 시각은 2026-10-25 12:00 KST로 고정한다.
 * 테스트끼리 DB를 같이 쓰므로 점포 ID·상품 코드·주문 ID를 테스트마다 다르게 둔다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class ReserveTest {

    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-25T12:00:00+09:00");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW.toInstant(), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired
    InventoryCommandService inventoryCommandService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Value("${app.queue.results}")
    String resultsQueue;

    @Test
    void reservesFromEarliestExpiryLotFirstAcrossTwoLots() {
        long storeId = 1201L;
        long productId = insertProduct("P1201", "FEFO 우유", 1500L);
        // 기한이 늦은 lot을 먼저 입고해 lot ID 순서와 유통기한 순서를 엇갈리게 한다
        inbound(storeId, "P1201", 5, "2026-11-05T23:59:59+09:00");
        inbound(storeId, "P1201", 2, "2026-10-28T23:59:59+09:00");

        inventoryCommandService.handle(reserve(1201L, storeId, productId, 3));

        List<Map<String, Object>> lots = jdbcTemplate.queryForList("""
                SELECT id, reserved_quantity
                FROM inventory_lot
                WHERE store_id = ?
                ORDER BY expiry_date
                """, storeId);
        assertThat(lots).extracting(row -> ((Number) row.get("reserved_quantity")).intValue())
                .containsExactly(2, 1);

        List<Map<String, Object>> reservations = jdbcTemplate.queryForList("""
                SELECT lot_id, quantity, status
                FROM reservation
                WHERE order_id = ?
                ORDER BY id
                """, 1201L);
        assertThat(reservations).hasSize(2);
        assertThat(reservations).extracting(row -> row.get("status"))
                .containsOnly("RESERVED");
        assertThat(reservations).extracting(row -> ((Number) row.get("quantity")).intValue())
                .containsExactly(2, 1);

        List<Map<String, Object>> outbox = outboxRows(1201L);
        assertThat(outbox).hasSize(1);
        Map<String, Object> row = outbox.getFirst();
        assertThat(row.get("message_type")).isEqualTo("RESERVED");
        assertThat(row.get("queue_name")).isEqualTo(resultsQueue);
        assertThat(row.get("group_id")).isNull();

        ReservedMessage reserved = (ReservedMessage) MessageJson.fromJson((String) row.get("payload"));
        assertThat(reserved.productName()).isEqualTo("FEFO 우유");
        assertThat(reserved.unitPrice()).isEqualTo(1500L);
        assertThat(reserved.quantity()).isEqualTo(3);
        assertThat(reserved.lots()).extracting(ReservedLot::lotId)
                .containsExactly(
                        ((Number) lots.get(0).get("id")).longValue(),
                        ((Number) lots.get(1).get("id")).longValue()
                );
        assertThat(reserved.lots()).extracting(ReservedLot::quantity)
                .containsExactly(2, 1);
        assertThat(reserved.lots().getFirst().expiryDate().toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-10-28T23:59:59+09:00").toInstant());
    }

    @Test
    void expiredLotIsExcludedFromReservation() {
        long storeId = 1202L;
        long productId = insertProduct("P1202", "경과 컵라면", 1700L);
        inbound(storeId, "P1202", 5, "2026-10-24T23:59:59+09:00");

        inventoryCommandService.handle(reserve(1202L, storeId, productId, 1));

        ReserveFailedMessage failed = singleFailed(1202L);
        assertThat(failed.reason()).isEqualTo(ReserveFailReason.OUT_OF_STOCK);
        assertThat(failed.availableQuantity()).isZero();
        assertThat(reservedQuantitySum(storeId)).isZero();
        assertThat(reservationCount(1202L)).isZero();
    }

    @Test
    void outOfStockSendsAvailableQuantityAndKeepsLots() {
        long storeId = 1203L;
        long productId = insertProduct("P1203", "부족 두부", 2000L);
        inbound(storeId, "P1203", 3, "2026-10-30T23:59:59+09:00");

        inventoryCommandService.handle(reserve(1203L, storeId, productId, 5));

        ReserveFailedMessage failed = singleFailed(1203L);
        assertThat(failed.reason()).isEqualTo(ReserveFailReason.OUT_OF_STOCK);
        assertThat(failed.availableQuantity()).isEqualTo(3);
        assertThat(reservedQuantitySum(storeId)).isZero();
        assertThat(reservationCount(1203L)).isZero();
    }

    @Test
    void sameMessageIdIsReservedOnlyOnce() {
        long storeId = 1204L;
        long productId = insertProduct("P1204", "멱등 우유", 1500L);
        inbound(storeId, "P1204", 5, "2026-10-30T23:59:59+09:00");

        ReserveMessage message = reserve(1204L, storeId, productId, 2);
        inventoryCommandService.handle(message);
        inventoryCommandService.handle(message);

        assertThat(reservationCount(1204L)).isEqualTo(1);
        assertThat(reservedQuantitySum(storeId)).isEqualTo(2);
        assertThat(outboxRows(1204L)).hasSize(1);
    }

    @Test
    void sameOrderIdWithNewMessageIdResendsSameReserved() {
        long storeId = 1205L;
        long productId = insertProduct("P1205", "재수신 우유", 1500L);
        inbound(storeId, "P1205", 2, "2026-10-28T23:59:59+09:00");
        inbound(storeId, "P1205", 5, "2026-11-05T23:59:59+09:00");

        inventoryCommandService.handle(reserve(1205L, storeId, productId, 3));
        inventoryCommandService.handle(reserve(1205L, storeId, productId, 3));

        assertThat(reservationCount(1205L)).isEqualTo(2);
        assertThat(reservedQuantitySum(storeId)).isEqualTo(3);

        List<Map<String, Object>> outbox = outboxRows(1205L);
        assertThat(outbox).hasSize(2);
        assertThat(outbox).extracting(row -> row.get("message_type"))
                .containsOnly("RESERVED");

        ReservedMessage first = (ReservedMessage) MessageJson.fromJson((String) outbox.get(0).get("payload"));
        ReservedMessage second = (ReservedMessage) MessageJson.fromJson((String) outbox.get(1).get("payload"));
        assertThat(second.messageId()).isNotEqualTo(first.messageId());
        assertThat(second.quantity()).isEqualTo(first.quantity());
        assertThat(second.unitPrice()).isEqualTo(first.unitPrice());
        assertThat(second.productName()).isEqualTo(first.productName());
        assertThat(second.lots()).extracting(ReservedLot::lotId, ReservedLot::quantity)
                .containsExactlyElementsOf(
                        first.lots().stream()
                                .map(lot -> tuple(lot.lotId(), lot.quantity()))
                                .toList()
                );
    }

    @Test
    void resendUsesReservedLotProductEvenIfRetryHasDifferentProduct() {
        long storeId = 1208L;
        long productId = insertProduct("P1208", "재수신 원래 상품", 1500L);
        long otherProductId = insertProduct("P1209", "재수신 다른 상품", 9900L);
        inbound(storeId, "P1208", 5, "2026-10-30T23:59:59+09:00");

        inventoryCommandService.handle(reserve(1208L, storeId, productId, 2));
        inventoryCommandService.handle(reserve(1208L, 9999L, otherProductId, 2));

        List<Map<String, Object>> outbox = outboxRows(1208L);
        assertThat(outbox).hasSize(2);
        ReservedMessage resent = (ReservedMessage) MessageJson.fromJson((String) outbox.get(1).get("payload"));
        assertThat(resent.storeId()).isEqualTo(storeId);
        assertThat(resent.productId()).isEqualTo(productId);
        assertThat(resent.productName()).isEqualTo("재수신 원래 상품");
        assertThat(resent.unitPrice()).isEqualTo(1500L);
        assertThat(resent.quantity()).isEqualTo(2);
    }

    @Test
    void unknownProductSendsProductNotFound() {
        inventoryCommandService.handle(reserve(1206L, 1206L, 999_999L, 1));

        ReserveFailedMessage failed = singleFailed(1206L);
        assertThat(failed.reason()).isEqualTo(ReserveFailReason.PRODUCT_NOT_FOUND);
        assertThat(failed.availableQuantity()).isZero();
    }

    @Test
    void zeroQuantitySendsInvalidQuantity() {
        long storeId = 1207L;
        long productId = insertProduct("P1207", "수량 0 우유", 1500L);
        inbound(storeId, "P1207", 5, "2026-10-30T23:59:59+09:00");

        inventoryCommandService.handle(reserve(1207L, storeId, productId, 0));

        ReserveFailedMessage failed = singleFailed(1207L);
        assertThat(failed.reason()).isEqualTo(ReserveFailReason.INVALID_QUANTITY);
        assertThat(failed.availableQuantity()).isZero();
        assertThat(reservedQuantitySum(storeId)).isZero();
    }

    private long insertProduct(String productCode, String name, long price) {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, name, "테스트", price);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private void inbound(long storeId, String productCode, int quantity, String expiryDate) {
        inventoryCommandService.handle(new InboundMessage(
                UUID.randomUUID().toString(),
                NOW,
                storeId,
                "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(expiryDate)))
        ));
    }

    private ReserveMessage reserve(long orderId, long storeId, long productId, int quantity) {
        return new ReserveMessage(UUID.randomUUID().toString(), NOW, orderId, storeId, productId, quantity);
    }

    private List<Map<String, Object>> outboxRows(long orderId) {
        return jdbcTemplate.queryForList("""
                SELECT message_type, queue_name, group_id, payload
                FROM outbox
                WHERE JSON_UNQUOTE(JSON_EXTRACT(payload, '$.orderId')) = ?
                ORDER BY id
                """, String.valueOf(orderId));
    }

    private ReserveFailedMessage singleFailed(long orderId) {
        List<Map<String, Object>> outbox = outboxRows(orderId);
        assertThat(outbox).hasSize(1);
        Map<String, Object> row = outbox.getFirst();
        assertThat(row.get("message_type")).isEqualTo("RESERVE_FAILED");
        assertThat(row.get("queue_name")).isEqualTo(resultsQueue);
        assertThat(row.get("group_id")).isNull();
        return (ReserveFailedMessage) MessageJson.fromJson((String) row.get("payload"));
    }

    private int reservedQuantitySum(long storeId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(reserved_quantity), 0) FROM inventory_lot WHERE store_id = ?",
                Integer.class, storeId);
    }

    private int reservationCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE order_id = ?", Integer.class, orderId);
    }
}
