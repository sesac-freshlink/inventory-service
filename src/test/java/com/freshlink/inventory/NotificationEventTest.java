package com.freshlink.inventory;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.common.message.command.ReturnMessage;
import com.freshlink.common.message.notification.NotificationKind;
import com.freshlink.common.message.notification.NotificationMessage;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import com.freshlink.inventory.domain.scheduler.LotStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화면 알림 쪽지(common 0.6.0 NotificationMessage): 유통기한 임박·경과는 상태가 바뀌는 lot마다 한 번,
 * 재입고는 INBOUND로 팔 수 있는 수량이 0 → 1 이상이 될 때만. 점포 ID·상품 코드는 56xx.
 * 지금 시각은 FixedClockConfiguration.NOW = 2026-10-25 12:00 KST.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class NotificationEventTest {

    static final String QUEUE = "dev-notification-events";
    static final String FAR = "2027-12-31T23:59:59+09:00";

    @Autowired
    LotStatusService lotStatusService;

    @Autowired
    InventoryCommandService inventoryCommandService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 임박으로_바뀌는_lot마다_한_번_임박_알림을_표준_큐로_남긴다() {
        lotStatusService.refresh();
        long storeId = 5601L;
        long productId = product("P5601", "알림 임박 우유");
        inbound(storeId, "P5601", 4, "2026-10-25T22:00:00+09:00");

        lotStatusService.refresh();
        lotStatusService.refresh();

        List<Map<String, Object>> rows = expiryRows(storeId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("message_type", "NOTIFICATION")
                .containsEntry("queue_name", QUEUE);
        assertThat(rows.getFirst().get("group_id")).isNull();
        NotificationMessage message = message(rows.getFirst());
        assertThat(message.kind()).isEqualTo(NotificationKind.EXPIRY_IMMINENT);
        assertThat(message.productId()).isEqualTo(productId);
        assertThat(message.productName()).isEqualTo("알림 임박 우유");
        assertThat(message.quantity()).isEqualTo(4);
        assertThat(message.lotId()).isEqualTo(lotId(storeId));
        assertThat(message.expiresAt()).isEqualTo(OffsetDateTime.parse("2026-10-25T22:00:00+09:00"));
        assertThat(message.userId()).isNull();
    }

    @Test
    void 기한이_지난_lot은_경과_알림_하나만_가고_임박이던_lot이_지나면_경과가_한_번_더_간다() {
        lotStatusService.refresh();
        long storeId = 5602L;
        product("P5602", "알림 경과 빵");
        // 바로 경과
        inbound(storeId, "P5602", 3, "2026-10-25T11:00:00+09:00");

        lotStatusService.refresh();
        assertThat(expiryKinds(storeId)).containsExactly(NotificationKind.EXPIRED);

        // 임박 lot이 나중에 기한을 넘긴 경우(시계를 고정해서 기한을 앞으로 당겨 흉내 낸다)
        long storeId2 = 5603L;
        product("P5603", "알림 임박 뒤 경과");
        inbound(storeId2, "P5603", 2, "2026-10-25T20:00:00+09:00");
        lotStatusService.refresh();
        jdbcTemplate.update("UPDATE inventory_lot SET expiry_date = ? WHERE store_id = ?",
                LocalDateTime.parse("2026-10-25T11:30:00"), storeId2);
        lotStatusService.refresh();

        assertThat(expiryKinds(storeId2)).containsExactly(NotificationKind.EXPIRY_IMMINENT, NotificationKind.EXPIRED);
    }

    @Test
    void 남은_수량이_0인_lot은_상태도_안_바꾸고_알림도_안_보낸다() {
        lotStatusService.refresh();
        long storeId = 5604L;
        product("P5604", "알림 빈 lot");
        inbound(storeId, "P5604", 3, "2026-10-25T20:00:00+09:00");
        jdbcTemplate.update("UPDATE inventory_lot SET quantity = 0 WHERE store_id = ?", storeId);

        lotStatusService.refresh();

        assertThat(expiryRows(storeId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM inventory_lot WHERE store_id = ?", String.class, storeId)).isEqualTo("NORMAL");
    }

    @Test
    void 품절이던_상품이_입고되면_재입고_알림을_남기고_이미_있던_상품에_더한_입고는_보내지_않는다() {
        long storeId = 5605L;
        long productId = product("P5605", "알림 재입고 라면");

        inbound(storeId, "P5605", 5, FAR);
        inbound(storeId, "P5605", 3, FAR);

        List<NotificationMessage> messages = messages(storeId);
        assertThat(messages).hasSize(1);
        NotificationMessage restocked = messages.getFirst();
        assertThat(restocked.kind()).isEqualTo(NotificationKind.RESTOCKED);
        assertThat(restocked.productId()).isEqualTo(productId);
        assertThat(restocked.productName()).isEqualTo("알림 재입고 라면");
        assertThat(restocked.quantity()).isEqualTo(5);
        assertThat(restocked.lotId()).isNull();
        assertThat(restocked.expiresAt()).isNull();
    }

    @Test
    void 남은_재고가_전부_예약이었거나_경과뿐이면_0으로_보고_입고_때_재입고_알림을_보낸다() {
        long storeId = 5606L;
        long reservedOnly = product("P5606", "알림 예약만 남음");
        product("P5607", "알림 경과만 남음");
        inbound(storeId, "P5606", 2, FAR);
        handle(new ReserveMessage(newId(), NOW, 5601L, storeId, reservedOnly, 2));
        inbound(storeId, "P5607", 2, "2026-10-24T23:59:59+09:00");
        int before = messages(storeId).size();

        inbound(storeId, "P5606", 4, FAR);
        inbound(storeId, "P5607", 1, FAR);

        List<NotificationMessage> after = messages(storeId).subList(before, messages(storeId).size());
        assertThat(after).extracting(NotificationMessage::kind)
                .containsExactly(NotificationKind.RESTOCKED, NotificationKind.RESTOCKED);
        assertThat(after).extracting(NotificationMessage::quantity).containsExactly(4, 1);
    }

    @Test
    void 기한_지난_상품만_입고되거나_환불로_되돌아온_수량은_재입고가_아니다() {
        long storeId = 5607L;
        long productId = product("P5608", "알림 환불 복원");
        inbound(storeId, "P5608", 2, FAR);           // 재입고 1번
        handle(new ReserveMessage(newId(), NOW, 5611L, storeId, productId, 2));
        handle(new ConfirmMessage(newId(), NOW, 5611L, storeId));   // 다 팔려 0
        handle(ReturnMessage.of(5611L, storeId));                    // 환불로 2개가 돌아와도 재입고 아님

        product("P5609", "알림 경과 입고");
        inbound(storeId, "P5609", 3, "2026-10-24T23:59:59+09:00");   // 들어오자마자 경과라 팔 수 없음

        assertThat(kinds(storeId)).containsExactly(NotificationKind.RESTOCKED);
    }

    private List<Map<String, Object>> outboxRows(long storeId) {
        return jdbcTemplate.queryForList("""
                SELECT message_type, queue_name, group_id, payload FROM outbox
                WHERE message_type = 'NOTIFICATION' AND JSON_EXTRACT(payload, '$.storeId') = ?
                ORDER BY id
                """, storeId);
    }

    /** 유통기한 알림만. 테스트 준비로 넣은 입고가 만든 재입고 알림은 뺀다 */
    private List<Map<String, Object>> expiryRows(long storeId) {
        return outboxRows(storeId).stream()
                .filter(row -> message(row).kind() != NotificationKind.RESTOCKED)
                .toList();
    }

    private List<NotificationKind> expiryKinds(long storeId) {
        return expiryRows(storeId).stream().map(row -> message(row).kind()).toList();
    }

    private List<NotificationMessage> messages(long storeId) {
        return outboxRows(storeId).stream().map(NotificationEventTest::message).toList();
    }

    private List<NotificationKind> kinds(long storeId) {
        return messages(storeId).stream().map(NotificationMessage::kind).toList();
    }

    private static NotificationMessage message(Map<String, Object> row) {
        return (NotificationMessage) MessageJson.fromJson((String) row.get("payload"));
    }

    private long lotId(long storeId) {
        return jdbcTemplate.queryForObject("SELECT id FROM inventory_lot WHERE store_id = ?", Long.class, storeId);
    }

    private void inbound(long storeId, String productCode, int quantity, String expiry) {
        handle(new InboundMessage(newId(), NOW, storeId, "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(expiry)))));
    }

    private void handle(BaseMessage message) {
        inventoryCommandService.handle(message);
    }

    private long product(String productCode, String name) {
        jdbcTemplate.update("INSERT INTO product (product_code, name, category, price) VALUES (?, ?, ?, ?)",
                productCode, name, "알림시험", 1000L);
        return jdbcTemplate.queryForObject("SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
