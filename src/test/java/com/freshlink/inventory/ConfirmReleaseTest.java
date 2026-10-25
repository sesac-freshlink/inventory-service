package com.freshlink.inventory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CONFIRM·RELEASE 처리(명세 5-4·5-5). 테스트끼리 DB를 같이 쓰므로 점포 ID·상품 코드·주문 ID를 테스트마다 다르게 둔다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class ConfirmReleaseTest {

    /** 이 테스트는 실제 시계를 쓰므로 유통기한을 늘 1년 뒤로 둔다. 날짜를 고정하면 그 날짜가 지난 뒤 예약이 실패한다 */
    static final String EXPIRY = OffsetDateTime.now(ZoneOffset.ofHours(9))
            .plusYears(1)
            .withHour(23).withMinute(59).withSecond(59).withNano(0)
            .toString();

    @Autowired
    InventoryCommandService inventoryCommandService;

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
    void confirmReducesQuantityAndReservedAndWritesSaleLedger() {
        long storeId = 1401L;
        long orderId = 1401L;
        long productId = insertProduct("P1401");
        inbound(storeId, "P1401", 10);
        reserve(orderId, storeId, productId, 3);

        inventoryCommandService.handle(confirm(orderId, storeId));

        Map<String, Object> lot = lot(storeId);
        assertThat(((Number) lot.get("quantity")).intValue()).isEqualTo(7);
        assertThat(((Number) lot.get("reserved_quantity")).intValue()).isZero();
        assertThat(reservationStatuses(orderId)).containsExactly("CONFIRMED");

        List<Map<String, Object>> sales = saleLedger(storeId);
        assertThat(sales).hasSize(1);
        assertThat(((Number) sales.getFirst().get("quantity")).intValue()).isEqualTo(-3);
        assertThat(sales.getFirst().get("ref_id")).isEqualTo(String.valueOf(orderId));
    }

    @Test
    void releaseRestoresReservedOnly() {
        long storeId = 1402L;
        long orderId = 1402L;
        long productId = insertProduct("P1402");
        inbound(storeId, "P1402", 10);
        reserve(orderId, storeId, productId, 4);

        inventoryCommandService.handle(release(orderId, storeId, ReleaseReason.EXPIRED));

        Map<String, Object> lot = lot(storeId);
        assertThat(((Number) lot.get("quantity")).intValue()).isEqualTo(10);
        assertThat(((Number) lot.get("reserved_quantity")).intValue()).isZero();
        assertThat(reservationStatuses(orderId)).containsExactly("RELEASED");
        assertThat(saleLedger(storeId)).isEmpty();
    }

    @Test
    void confirmAfterReleaseKeepsStockAndLogsOneError() {
        long storeId = 1403L;
        long orderId = 1403L;
        long productId = insertProduct("P1403");
        inbound(storeId, "P1403", 10);
        reserve(orderId, storeId, productId, 2);
        inventoryCommandService.handle(release(orderId, storeId, ReleaseReason.PAYMENT_FAILED));

        inventoryCommandService.handle(confirm(orderId, storeId));

        Map<String, Object> lot = lot(storeId);
        assertThat(((Number) lot.get("quantity")).intValue()).isEqualTo(10);
        assertThat(((Number) lot.get("reserved_quantity")).intValue()).isZero();
        assertThat(reservationStatuses(orderId)).containsExactly("RELEASED");
        assertThat(saleLedger(storeId)).isEmpty();
        assertThat(errorLogs()).hasSize(1);
    }

    @Test
    void confirmWithoutReservationEndsNormallyAndIsRecorded() {
        ConfirmMessage message = confirm(1404L, 1404L);

        assertThatCode(() -> inventoryCommandService.handle(message))
                .doesNotThrowAnyException();

        Integer processedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_message WHERE message_id = ?",
                Integer.class, message.messageId());
        assertThat(processedCount).isEqualTo(1);
        assertThat(errorLogs()).hasSize(1);
    }

    @Test
    void confirmTwiceWithNewMessageIdConfirmsOnlyOnce() {
        long storeId = 1405L;
        long orderId = 1405L;
        long productId = insertProduct("P1405");
        inbound(storeId, "P1405", 10);
        reserve(orderId, storeId, productId, 3);

        inventoryCommandService.handle(confirm(orderId, storeId));
        inventoryCommandService.handle(confirm(orderId, storeId));

        assertThat(((Number) lot(storeId).get("quantity")).intValue()).isEqualTo(7);
        assertThat(saleLedger(storeId)).hasSize(1);
        assertThat(errorLogs()).isEmpty();
    }

    @Test
    void confirmAndReleaseWithOtherStoreIdChangeNothing() {
        long storeId = 1407L;
        long orderId = 1407L;
        long productId = insertProduct("P1407");
        inbound(storeId, "P1407", 10);
        reserve(orderId, storeId, productId, 3);

        assertThatThrownBy(() -> inventoryCommandService.handle(confirm(orderId, 9999L)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inventoryCommandService.handle(release(orderId, 9999L, ReleaseReason.EXPIRED)))
                .isInstanceOf(IllegalStateException.class);

        Map<String, Object> lot = lot(storeId);
        assertThat(((Number) lot.get("quantity")).intValue()).isEqualTo(10);
        assertThat(((Number) lot.get("reserved_quantity")).intValue()).isEqualTo(3);
        assertThat(reservationStatuses(orderId)).containsExactly("RESERVED");
        assertThat(saleLedger(storeId)).isEmpty();
    }

    @Test
    void ledgerSumEqualsLotQuantityAfterInboundAndSale() {
        long storeId = 1406L;
        long orderId = 1406L;
        long productId = insertProduct("P1406");
        inbound(storeId, "P1406", 10);
        reserve(orderId, storeId, productId, 3);
        inventoryCommandService.handle(confirm(orderId, storeId));

        Map<String, Object> lot = lot(storeId);
        Integer ledgerSum = jdbcTemplate.queryForObject(
                "SELECT SUM(quantity) FROM ledger WHERE lot_id = ?",
                Integer.class, lot.get("id"));

        assertThat(ledgerSum).isEqualTo(7);
        assertThat(((Number) lot.get("quantity")).intValue()).isEqualTo(ledgerSum);
    }

    private long insertProduct(String productCode) {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, "확정 해제 " + productCode, "테스트", 1500L);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private void inbound(long storeId, String productCode, int quantity) {
        inventoryCommandService.handle(new InboundMessage(
                UUID.randomUUID().toString(),
                OffsetDateTime.now(),
                storeId,
                "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(EXPIRY)))
        ));
    }

    private void reserve(long orderId, long storeId, long productId, int quantity) {
        inventoryCommandService.handle(new ReserveMessage(
                UUID.randomUUID().toString(), OffsetDateTime.now(), orderId, storeId, productId, quantity));
    }

    private ConfirmMessage confirm(long orderId, long storeId) {
        return new ConfirmMessage(UUID.randomUUID().toString(), OffsetDateTime.now(), orderId, storeId);
    }

    private ReleaseMessage release(long orderId, long storeId, ReleaseReason reason) {
        return new ReleaseMessage(UUID.randomUUID().toString(), OffsetDateTime.now(), orderId, storeId, reason);
    }

    private Map<String, Object> lot(long storeId) {
        return jdbcTemplate.queryForMap(
                "SELECT id, quantity, reserved_quantity FROM inventory_lot WHERE store_id = ?", storeId);
    }

    private List<String> reservationStatuses(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM reservation WHERE order_id = ? ORDER BY id", String.class, orderId);
    }

    private List<Map<String, Object>> saleLedger(long storeId) {
        return jdbcTemplate.queryForList(
                "SELECT quantity, ref_id FROM ledger WHERE store_id = ? AND type = 'SALE'", storeId);
    }

    private List<ILoggingEvent> errorLogs() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
    }
}
