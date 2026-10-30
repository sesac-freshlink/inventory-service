package com.freshlink.inventory;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.common.message.command.ReturnMessage;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import com.freshlink.inventory.domain.stats.InventoryStatsResponse;
import com.freshlink.inventory.domain.stats.InventoryStatsService;
import com.freshlink.inventory.domain.stats.SalesRankingResponse;
import com.freshlink.inventory.domain.stats.SalesRankingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * common v0.5.0: RELEASE 사유 CANCELED(결제 전 고객 취소, A7)와 RETURN(환불 뒤 판매 재고 복원, A10).
 * 테스트끼리 DB를 같이 쓰므로 점포 ID·상품 코드·주문 ID를 53xx로 따로 둔다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class ReturnCanceledTest {

    static final String EXPIRY = "2027-12-31T23:59:59+09:00";

    @Autowired
    InventoryCommandService inventoryCommandService;

    @Autowired
    SalesRankingService salesRankingService;

    @Autowired
    InventoryStatsService inventoryStatsService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void CANCELED_사유의_RELEASE도_쪽지로_읽혀_예약만_되돌린다() {
        long storeId = 5301L;
        long productId = product("P5301");
        inbound(storeId, "P5301", 10);
        handle(new ReserveMessage(newId(), NOW, 5301L, storeId, productId, 4));

        // order가 보내는 그대로 JSON을 거쳐 읽는다. common 0.4.0은 이 값을 읽지 못했다
        String json = MessageJson.toJson(new ReleaseMessage(newId(), NOW, 5301L, storeId, ReleaseReason.CANCELED));
        handle(MessageJson.fromJson(json));

        assertThat(lot(storeId)).containsEntry("quantity", 10).containsEntry("reserved_quantity", 0);
        assertThat(reservationStatuses(5301L)).containsExactly("RELEASED");
        assertThat(available(storeId, productId)).isEqualTo(10);
    }

    @Test
    void RETURN은_판매한_lot에_수량을_되돌리고_원장에_RETURN을_남긴다() {
        long storeId = 5302L;
        long productId = product("P5302");
        inbound(storeId, "P5302", 10);
        sell(5302L, storeId, productId, 3);

        String json = MessageJson.toJson(ReturnMessage.of(5302L, storeId));
        handle(MessageJson.fromJson(json));

        assertThat(lot(storeId)).containsEntry("quantity", 10).containsEntry("reserved_quantity", 0);
        List<Map<String, Object>> returns = ledger(storeId, "RETURN");
        assertThat(returns).hasSize(1);
        assertThat(returns.getFirst()).containsEntry("quantity", 3).containsEntry("ref_id", "5302");
        // lot별 원장 합계 = lot 수량 (입고 +10, 판매 −3, 복원 +3)
        assertThat(ledgerSum(storeId)).isEqualTo(10);
        assertThat(available(storeId, productId)).isEqualTo(10);
    }

    @Test
    void 두_lot에_나뉘어_팔린_주문은_lot마다_되돌린다() {
        long storeId = 5303L;
        long productId = product("P5303");
        inbound(storeId, "P5303", 2, "2027-06-30T23:59:59+09:00");
        inbound(storeId, "P5303", 5, EXPIRY);
        // FEFO: 기한 빠른 lot 2개 + 다음 lot 1개
        sell(5303L, storeId, productId, 3);

        handle(ReturnMessage.of(5303L, storeId));

        assertThat(jdbcTemplate.queryForList(
                "SELECT quantity FROM inventory_lot WHERE store_id = ? ORDER BY expiry_date", Integer.class, storeId))
                .containsExactly(2, 5);
        assertThat(ledger(storeId, "RETURN")).extracting(row -> row.get("quantity")).containsExactly(2, 1);
    }

    @Test
    void 같은_주문의_RETURN이_messageId를_바꿔_다시_와도_한_번만_되돌린다() {
        long storeId = 5304L;
        long productId = product("P5304");
        inbound(storeId, "P5304", 10);
        sell(5304L, storeId, productId, 4);

        handle(ReturnMessage.of(5304L, storeId));
        handle(ReturnMessage.of(5304L, storeId));

        assertThat(lot(storeId)).containsEntry("quantity", 10);
        assertThat(ledger(storeId, "RETURN")).hasSize(1);
    }

    @Test
    void 판매_기록이_없는_RETURN은_아무것도_바꾸지_않고_예외로_끝나_재시도된다() {
        long storeId = 5305L;
        long productId = product("P5305");
        inbound(storeId, "P5305", 10);
        // 예약만 하고 확정 전
        handle(new ReserveMessage(newId(), NOW, 5305L, storeId, productId, 2));
        ReturnMessage message = ReturnMessage.of(5305L, storeId);

        assertThatThrownBy(() -> handle(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orderId=5305");

        assertThat(lot(storeId)).containsEntry("quantity", 10).containsEntry("reserved_quantity", 2);
        assertThat(ledger(storeId, "RETURN")).isEmpty();
        // 트랜잭션이 통째로 되돌아가 처리 기록도 없다. SQS가 다시 보내면 처음부터 다시 처리한다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_message WHERE message_id = ?", Integer.class, message.messageId()))
                .isZero();
    }

    @Test
    void 기한이_지난_lot도_원래_lot에_되돌리되_구매_가능_수량에는_들어가지_않는다() {
        long storeId = 5306L;
        long productId = product("P5306");
        inbound(storeId, "P5306", 5);
        sell(5306L, storeId, productId, 2);
        jdbcTemplate.update("UPDATE inventory_lot SET expiry_date = ? WHERE store_id = ?",
                NOW.toLocalDateTime().minusHours(1), storeId);

        handle(ReturnMessage.of(5306L, storeId));

        assertThat(lot(storeId)).containsEntry("quantity", 5);
        assertThat(available(storeId, productId)).isZero();
    }

    @Test
    void 판매_순위와_통계는_환불로_되돌린_수량을_뺀다() {
        long storeId = 5307L;
        long a = product("P5307");
        long b = product("P5308");
        inbound(storeId, "P5307", 10);
        inbound(storeId, "P5308", 10);
        sell(5307L, storeId, a, 5);
        sell(5308L, storeId, b, 3);
        sell(5309L, storeId, b, 1);
        // a는 5개 팔고 5개 환불 → 순판매 0이라 순위에서 빠진다. b는 4개 팔고 1개 환불 → 3
        handle(ReturnMessage.of(5307L, storeId));
        handle(ReturnMessage.of(5309L, storeId));

        SalesRankingResponse ranking = salesRankingService.getRanking(storeId, null, null, null);
        assertThat(ranking.totalSoldQuantity()).isEqualTo(3);
        assertThat(ranking.items()).hasSize(1);
        assertThat(ranking.items().getFirst().productId()).isEqualTo(b);
        assertThat(ranking.items().getFirst().soldQuantity()).isEqualTo(3);

        InventoryStatsResponse stats = inventoryStatsService.getStats(storeId, null, null);
        assertThat(stats.soldQuantity()).isEqualTo(3);
        assertThat(stats.returnedQuantity()).isEqualTo(6);
        assertThat(stats.products())
                .filteredOn(row -> row.productId().equals(a))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.soldQuantity()).isZero();
                    assertThat(row.returnedQuantity()).isEqualTo(5);
                });
    }

    @Test
    void RETURN은_변동_이력_종류로_거를_수_있다() {
        long storeId = 5310L;
        long productId = product("P5310");
        inbound(storeId, "P5310", 10);
        sell(5310L, storeId, productId, 2);
        handle(ReturnMessage.of(5310L, storeId));

        assertThat(jdbcTemplate.queryForList(
                "SELECT type FROM ledger WHERE store_id = ? ORDER BY id", String.class, storeId))
                .containsExactly("INBOUND", "SALE", "RETURN");
    }

    private void sell(long orderId, long storeId, long productId, int quantity) {
        handle(new ReserveMessage(newId(), NOW, orderId, storeId, productId, quantity));
        handle(new ConfirmMessage(newId(), NOW, orderId, storeId));
    }

    private void inbound(long storeId, String productCode, int quantity) {
        inbound(storeId, productCode, quantity, EXPIRY);
    }

    private void inbound(long storeId, String productCode, int quantity, String expiry) {
        handle(new InboundMessage(newId(), NOW, storeId, "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, java.time.OffsetDateTime.parse(expiry)))));
    }

    private void handle(BaseMessage message) {
        inventoryCommandService.handle(message);
    }

    private long product(String productCode) {
        jdbcTemplate.update("INSERT INTO product (product_code, name, category, price) VALUES (?, ?, ?, ?)",
                productCode, "환불 " + productCode, "테스트", 1000L);
        return jdbcTemplate.queryForObject("SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private Map<String, Object> lot(long storeId) {
        return jdbcTemplate.queryForMap(
                "SELECT quantity, reserved_quantity FROM inventory_lot WHERE store_id = ?", storeId);
    }

    private List<String> reservationStatuses(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM reservation WHERE order_id = ? ORDER BY id", String.class, orderId);
    }

    private List<Map<String, Object>> ledger(long storeId, String type) {
        return jdbcTemplate.queryForList(
                "SELECT quantity, ref_id FROM ledger WHERE store_id = ? AND type = ? ORDER BY id", storeId, type);
    }

    private Integer ledgerSum(long storeId) {
        return jdbcTemplate.queryForObject("SELECT SUM(quantity) FROM ledger WHERE store_id = ?", Integer.class, storeId);
    }

    private Integer available(long storeId, long productId) {
        LocalDateTime now = NOW.toLocalDateTime();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT available_quantity, valid_until FROM store_product_stock WHERE store_id = ? AND product_id = ?",
                storeId, productId);
        // 화면과 같은 규칙: valid_until이 지났으면 품절로 본다
        LocalDateTime validUntil = (LocalDateTime) row.get("valid_until");
        if (validUntil == null || !validUntil.isAfter(now)) {
            return 0;
        }
        return ((Number) row.get("available_quantity")).intValue();
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
