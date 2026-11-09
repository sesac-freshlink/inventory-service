package com.freshlink.inventory;

import com.freshlink.common.message.BaseMessage;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마감세일 함께 사기 RESERVE (common 0.8.0, 김경우와 정한 D안). 지금 시각은 2026-10-25 12:00 KST(판매 시간 안).
 * 정가 1500원의 마감세일 단가는 1050원이다. 점포 6101~, 상품 P61xx, 주문 61xx를 쓴다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class ClearanceReserveTest {

    static final String TONIGHT = "2026-10-25T23:59:59+09:00";
    static final String NEXT_NOON = "2026-10-26T12:00:00+09:00";
    static final String FRESH = "2026-11-05T23:59:59+09:00";

    @Autowired
    InventoryCommandService inventoryCommandService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 마감세일과_기본을_lot별_단가로_나눠_잡는다() {
        long storeId = 6101L;
        long productId = product("P6101");
        handle(inbound(storeId, "P6101", 2, TONIGHT));
        handle(inbound(storeId, "P6101", 5, FRESH));

        handle(reserve(6101L, storeId, productId, 3, 1));

        ReservedMessage reserved = reserved(6101L);
        // RESERVED 단가는 기본 단가 그대로, 합계는 전체 수량이다
        assertThat(reserved.unitPrice()).isEqualTo(1500L);
        assertThat(reserved.quantity()).isEqualTo(3);
        assertThat(reserved.lots()).hasSize(2);
        ReservedLot clearance = reserved.lots().get(0);
        assertThat(clearance.clearance()).isTrue();
        assertThat(clearance.quantity()).isEqualTo(1);
        assertThat(clearance.unitPrice()).isEqualTo(1050L);
        ReservedLot regular = reserved.lots().get(1);
        assertThat(regular.clearance()).isFalse();
        assertThat(regular.quantity()).isEqualTo(2);
        assertThat(regular.unitPrice()).isNull();

        assertThat(reservedByExpiry(storeId)).containsExactly(1, 2);
    }

    @Test
    void 기한_빠른_마감세일_lot부터_잡고_24시간_경계_lot은_마감세일이다() {
        long storeId = 6102L;
        long productId = product("P6102");
        // 지금부터 정확히 24시간 뒤 lot은 마감세일이다(지금 < 기한 ≤ 지금 + 24시간)
        handle(inbound(storeId, "P6102", 2, NEXT_NOON));
        handle(inbound(storeId, "P6102", 1, TONIGHT));

        handle(reserve(6102L, storeId, productId, 3, 3));

        ReservedMessage reserved = reserved(6102L);
        assertThat(reserved.lots()).allMatch(ReservedLot::clearance);
        assertThat(reserved.lots()).extracting(lot -> lot.expiryDate().toInstant())
                .containsExactly(OffsetDateTime.parse(TONIGHT).toInstant(), OffsetDateTime.parse(NEXT_NOON).toInstant());
        assertThat(reservedByExpiry(storeId)).containsExactly(1, 2);
    }

    @Test
    void 기본_주문은_마감세일_lot을_건너뛰고_모자라면_OUT_OF_STOCK이다() {
        long storeId = 6103L;
        long productId = product("P6103");
        handle(inbound(storeId, "P6103", 5, TONIGHT));
        handle(inbound(storeId, "P6103", 1, FRESH));

        handle(reserve(6103L, storeId, productId, 2, 0));

        ReserveFailedMessage failed = failed(6103L);
        assertThat(failed.reason()).isEqualTo(ReserveFailReason.OUT_OF_STOCK);
        assertThat(failed.availableQuantity()).isEqualTo(1);
        // 마감세일을 요청하지 않은 주문에는 마감세일 남은 수량을 넣지 않는다
        assertThat(failed.clearanceAvailableQuantity()).isNull();
        assertThat(reservedByExpiry(storeId)).containsExactly(0, 0);
    }

    @Test
    void 마감세일이_모자라면_CLEARANCE_SOLD_OUT이고_재고는_그대로다() {
        long storeId = 6104L;
        long productId = product("P6104");
        handle(inbound(storeId, "P6104", 1, TONIGHT));
        handle(inbound(storeId, "P6104", 4, FRESH));

        handle(reserve(6104L, storeId, productId, 3, 2));

        ReserveFailedMessage failed = failed(6104L);
        assertThat(failed.reason()).isEqualTo(ReserveFailReason.CLEARANCE_SOLD_OUT);
        assertThat(failed.availableQuantity()).isEqualTo(4);
        assertThat(failed.clearanceAvailableQuantity()).isEqualTo(1);
        assertThat(reservedByExpiry(storeId)).containsExactly(0, 0);
    }

    @Test
    void 마감세일과_기본이_둘_다_모자라면_CLEARANCE_SOLD_OUT을_먼저_알리고_두_수량을_채운다() {
        long storeId = 6105L;
        long productId = product("P6105");
        handle(inbound(storeId, "P6105", 1, TONIGHT));
        handle(inbound(storeId, "P6105", 1, FRESH));

        handle(reserve(6105L, storeId, productId, 5, 2));

        ReserveFailedMessage failed = failed(6105L);
        assertThat(failed.reason()).isEqualTo(ReserveFailReason.CLEARANCE_SOLD_OUT);
        assertThat(failed.availableQuantity()).isEqualTo(1);
        assertThat(failed.clearanceAvailableQuantity()).isEqualTo(1);
    }

    @Test
    void 기본분만_모자라면_OUT_OF_STOCK이고_마감세일_남은_수량도_준다() {
        long storeId = 6106L;
        long productId = product("P6106");
        handle(inbound(storeId, "P6106", 3, TONIGHT));
        handle(inbound(storeId, "P6106", 1, FRESH));

        handle(reserve(6106L, storeId, productId, 4, 1));

        ReserveFailedMessage failed = failed(6106L);
        assertThat(failed.reason()).isEqualTo(ReserveFailReason.OUT_OF_STOCK);
        assertThat(failed.availableQuantity()).isEqualTo(1);
        assertThat(failed.clearanceAvailableQuantity()).isEqualTo(3);
    }

    @Test
    void 마감세일_수량이_전체보다_많거나_음수면_INVALID_QUANTITY다() {
        long storeId = 6107L;
        long productId = product("P6107");
        handle(inbound(storeId, "P6107", 5, TONIGHT));

        handle(reserve(6107L, storeId, productId, 1, 2));
        handle(reserve(6108L, storeId, productId, 1, -1));

        assertThat(failed(6107L).reason()).isEqualTo(ReserveFailReason.INVALID_QUANTITY);
        assertThat(failed(6108L).reason()).isEqualTo(ReserveFailReason.INVALID_QUANTITY);
        assertThat(reservedByExpiry(storeId)).containsExactly(0);
    }

    @Test
    void 선착순_상품에_마감세일을_요청하면_INVALID_QUANTITY다() {
        long storeId = 6109L;
        long productId = product("P6109");
        handle(inbound(storeId, "P6109", 5, TONIGHT));
        firstCome(storeId, productId);

        handle(reserve(6109L, storeId, productId, 1, 1));

        assertThat(failed(6109L).reason()).isEqualTo(ReserveFailReason.INVALID_QUANTITY);
        assertThat(reservedByExpiry(storeId)).containsExactly(0);
    }

    @Test
    void 선착순_상품의_기본_주문은_24시간_안_lot도_기본가로_잡는다() {
        long storeId = 6110L;
        long productId = product("P6110");
        handle(inbound(storeId, "P6110", 5, TONIGHT));
        firstCome(storeId, productId);

        handle(reserve(6110L, storeId, productId, 2, 0));

        ReservedMessage reserved = reserved(6110L);
        assertThat(reserved.lots()).hasSize(1);
        assertThat(reserved.lots().getFirst().clearance()).isFalse();
        assertThat(reserved.lots().getFirst().unitPrice()).isNull();
    }

    @Test
    void 같은_주문이_다시_오면_처음과_같은_단가로_RESERVED를_다시_보낸다() {
        long storeId = 6111L;
        long productId = product("P6111");
        handle(inbound(storeId, "P6111", 2, TONIGHT));
        handle(inbound(storeId, "P6111", 5, FRESH));
        handle(reserve(6111L, storeId, productId, 3, 1));

        // 그 사이 점주가 가격을 바꿔도 처음 잡은 마감세일 단가를 그대로 돌려준다
        jdbcTemplate.update("UPDATE product SET price = 9990 WHERE id = ?", productId);
        handle(reserve(6111L, storeId, productId, 3, 1));

        List<Map<String, Object>> rows = outbox(6111L);
        assertThat(rows).hasSize(2);
        ReservedMessage again = (ReservedMessage) MessageJson.fromJson((String) rows.get(1).get("payload"));
        assertThat(again.lots()).extracting(ReservedLot::clearance).containsExactly(true, false);
        assertThat(again.lots().get(0).unitPrice()).isEqualTo(1050L);
        assertThat(again.lots().get(1).unitPrice()).isNull();
        assertThat(reservedByExpiry(storeId)).containsExactly(1, 2);
    }

    @Test
    void 칸이_없는_옛_RESERVE_쪽지는_기본_주문으로_읽는다() {
        long storeId = 6112L;
        long productId = product("P6112");
        handle(inbound(storeId, "P6112", 3, FRESH));
        String oldBody = """
                {"messageId":"%s","type":"RESERVE","createdAt":"2026-10-25T12:00:00+09:00",
                 "orderId":6112,"storeId":%d,"productId":%d,"quantity":1}
                """.formatted(UUID.randomUUID(), storeId, productId);

        handle(MessageJson.fromJson(oldBody));

        assertThat(reserved(6112L).lots()).extracting(ReservedLot::clearance).containsExactly(false);
    }

    private void handle(BaseMessage message) {
        inventoryCommandService.handle(message);
    }

    private static InboundMessage inbound(long storeId, String productCode, int quantity, String expiry) {
        return new InboundMessage(UUID.randomUUID().toString(), NOW, storeId, "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(expiry))));
    }

    private static ReserveMessage reserve(long orderId, long storeId, long productId, int quantity, int clearanceQuantity) {
        return new ReserveMessage(UUID.randomUUID().toString(), NOW, orderId, storeId, productId, quantity, clearanceQuantity);
    }

    private long product(String productCode) {
        jdbcTemplate.update("INSERT INTO product (product_code, name, category, price) VALUES (?, ?, ?, ?)",
                productCode, "마감세일 " + productCode, "테스트", 1500L);
        return jdbcTemplate.queryForObject("SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private void firstCome(long storeId, long productId) {
        jdbcTemplate.update("""
                INSERT INTO first_come_sale (store_id, product_id, sale_limit, started_at, created_at, updated_at)
                VALUES (?, ?, 100, ?, ?, ?)
                """, storeId, productId, NOW.toLocalDateTime(), NOW.toLocalDateTime(), NOW.toLocalDateTime());
    }

    private List<Integer> reservedByExpiry(long storeId) {
        return jdbcTemplate.queryForList(
                "SELECT reserved_quantity FROM inventory_lot WHERE store_id = ? ORDER BY expiry_date", Integer.class, storeId);
    }

    private List<Map<String, Object>> outbox(long orderId) {
        return jdbcTemplate.queryForList("""
                SELECT message_type, payload FROM outbox
                WHERE JSON_UNQUOTE(JSON_EXTRACT(payload, '$.orderId')) = ?
                ORDER BY id
                """, String.valueOf(orderId));
    }

    private ReservedMessage reserved(long orderId) {
        List<Map<String, Object>> rows = outbox(orderId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("message_type")).isEqualTo("RESERVED");
        return (ReservedMessage) MessageJson.fromJson((String) rows.getFirst().get("payload"));
    }

    private ReserveFailedMessage failed(long orderId) {
        List<Map<String, Object>> rows = outbox(orderId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("message_type")).isEqualTo("RESERVE_FAILED");
        return (ReserveFailedMessage) MessageJson.fromJson((String) rows.getFirst().get("payload"));
    }
}
