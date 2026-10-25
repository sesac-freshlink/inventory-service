package com.freshlink.inventory;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.DisposeMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-32 폐기 API와 DISPOSE 처리(명세 4 S-32, 5-6). 지금 시각은 2026-10-25 12:00 KST로 고정한다.
 * 임박 lot = 10/25 23:59:59, 경과 lot = 10/24 23:59:59, 정상 lot = 2027-12-31.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
@AutoConfigureMockMvc
class DisposalTest {

    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-25T12:00:00+09:00");
    static final String IMMINENT = "2026-10-25T23:59:59+09:00";
    static final String EXPIRED = "2026-10-24T23:59:59+09:00";
    static final String NORMAL = "2027-12-31T23:59:59+09:00";
    static final String USER_ID = "11111111-2222-3333-4444-555555555555";

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW.toInstant(), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    InventoryCommandService inventoryCommandService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Value("${app.queue.commands}")
    String commandsQueue;

    @Test
    void acceptedSavesRequestedDisposalAndDisposeOutbox() throws Exception {
        long lotId = lotOf(1L, "P1701", 10, EXPIRED);

        Number disposalId = (Number) JsonPath.read(
                requestDisposal("1", lotId, 3)
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.disposalId").isNumber())
                        .andExpect(jsonPath("$.lotId").value(lotId))
                        .andExpect(jsonPath("$.quantity").value(3))
                        .andReturn().getResponse().getContentAsString(),
                "$.disposalId"
        );

        Map<String, Object> disposal = jdbcTemplate.queryForMap(
                "SELECT store_id, lot_id, quantity, requested_by, status FROM disposal WHERE id = ?",
                disposalId.longValue());
        assertThat(disposal.get("status")).isEqualTo("REQUESTED");
        assertThat(disposal.get("requested_by")).isEqualTo(USER_ID);
        assertThat(((Number) disposal.get("quantity")).intValue()).isEqualTo(3);

        Map<String, Object> outbox = disposeOutbox(disposalId.longValue());
        assertThat(outbox.get("message_type")).isEqualTo("DISPOSE");
        assertThat(outbox.get("queue_name")).isEqualTo(commandsQueue);
        assertThat(outbox.get("group_id")).isEqualTo("1");

        DisposeMessage message = (DisposeMessage) MessageJson.fromJson((String) outbox.get("payload"));
        assertThat(message.lotId()).isEqualTo(lotId);
        assertThat(message.quantity()).isEqualTo(3);
        assertThat(message.requestedBy()).isEqualTo(USER_ID);

        assertThat(lotQuantity(lotId)).isEqualTo(10);
    }

    @Test
    void unknownLotIs404() throws Exception {
        requestDisposal("1702", 999_999L, 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOT_NOT_FOUND"));
    }

    @Test
    void otherStoreLotIs403() throws Exception {
        long lotId = lotOf(1703L, "P1703", 10, EXPIRED);

        requestDisposal("1704", lotId, 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOT_NOT_IN_STORE"));
        assertThat(disposalCount(lotId)).isZero();
    }

    @Test
    void normalLotIs409() throws Exception {
        long lotId = lotOf(1705L, "P1705", 10, NORMAL);

        requestDisposal("1705", lotId, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_DISPOSABLE"));
        assertThat(disposalCount(lotId)).isZero();
    }

    @Test
    void zeroQuantityIs400ValidationError() throws Exception {
        long lotId = lotOf(1706L, "P1706", 10, EXPIRED);

        requestDisposal("1706", lotId, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(disposalCount(lotId)).isZero();
    }

    @Test
    void quantityOverDisposableIs400InvalidQuantity() throws Exception {
        long lotId = lotOf(1707L, "P1707", 5, IMMINENT);
        handle(reserve(1707L, 1707L, productId("P1707"), 2));

        requestDisposal("1707", lotId, 4)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));
        assertThat(disposalCount(lotId)).isZero();
    }

    @Test
    void missingStoreIdIs403StoreNotLinked() throws Exception {
        mockMvc.perform(post("/owner/disposals")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotId\":1,\"quantity\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_NOT_LINKED"));
    }

    @Test
    void disposeMessageReducesQuantityAndWritesLedger() throws Exception {
        long lotId = lotOf(1708L, "P1708", 10, EXPIRED);
        long disposalId = acceptedDisposalId("1708", lotId, 3);

        handle(disposeMessage(disposalId));

        assertThat(lotQuantity(lotId)).isEqualTo(7);
        assertThat(disposalStatus(disposalId)).isEqualTo("DONE");
        Map<String, Object> ledger = jdbcTemplate.queryForMap(
                "SELECT quantity, ref_id FROM ledger WHERE lot_id = ? AND type = 'DISPOSE'", lotId);
        assertThat(((Number) ledger.get("quantity")).intValue()).isEqualTo(-3);
        assertThat(ledger.get("ref_id")).isEqualTo(String.valueOf(disposalId));
    }

    @Test
    void disposeIsSkippedWhenReservationTookStockBeforeProcessing() throws Exception {
        long lotId = lotOf(1709L, "P1709", 5, IMMINENT);
        long disposalId = acceptedDisposalId("1709", lotId, 5);
        handle(reserve(1709L, 1709L, productId("P1709"), 2));

        handle(disposeMessage(disposalId));

        assertThat(lotQuantity(lotId)).isEqualTo(5);
        assertThat(disposalStatus(disposalId)).isEqualTo("SKIPPED");
        Integer disposeLedgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger WHERE lot_id = ? AND type = 'DISPOSE'", Integer.class, lotId);
        assertThat(disposeLedgerCount).isZero();
    }

    @Test
    void disposeMessageTwiceIsAppliedOnce() throws Exception {
        long lotId = lotOf(1710L, "P1710", 10, EXPIRED);
        long disposalId = acceptedDisposalId("1710", lotId, 3);
        DisposeMessage original = disposeMessage(disposalId);

        handle(original);
        handle(new DisposeMessage(UUID.randomUUID().toString(), NOW, original.disposalId(),
                original.storeId(), original.lotId(), original.quantity(), original.requestedBy()));

        assertThat(lotQuantity(lotId)).isEqualTo(7);
        assertThat(disposalStatus(disposalId)).isEqualTo("DONE");
    }

    @Test
    void ledgerSumEqualsLotQuantityAfterInboundSaleAndDispose() throws Exception {
        long storeId = 1711L;
        long lotId = lotOf(storeId, "P1711", 10, IMMINENT);
        handle(reserve(1711L, storeId, productId("P1711"), 1));
        handle(new ConfirmMessage(UUID.randomUUID().toString(), NOW, 1711L, storeId));
        long disposalId = acceptedDisposalId(String.valueOf(storeId), lotId, 3);
        handle(disposeMessage(disposalId));

        Integer ledgerSum = jdbcTemplate.queryForObject(
                "SELECT SUM(quantity) FROM ledger WHERE lot_id = ?", Integer.class, lotId);
        assertThat(ledgerSum).isEqualTo(6);
        assertThat(lotQuantity(lotId)).isEqualTo(6);
    }

    private ResultActions requestDisposal(String storeId, long lotId, int quantity) throws Exception {
        return mockMvc.perform(post("/owner/disposals")
                .header("X-Store-Id", storeId)
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lotId\":" + lotId + ",\"quantity\":" + quantity + "}"));
    }

    private long acceptedDisposalId(String storeId, long lotId, int quantity) throws Exception {
        String body = requestDisposal(storeId, lotId, quantity)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.disposalId")).longValue();
    }

    /** 상품을 만들고 INBOUND로 lot 하나를 입고한 뒤 lot ID를 돌려준다 */
    private long lotOf(long storeId, String productCode, int quantity, String expiryDate) {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, "폐기 " + productCode, "테스트", 1500L);
        handle(new InboundMessage(
                UUID.randomUUID().toString(),
                NOW,
                storeId,
                "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(expiryDate)))
        ));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_lot WHERE store_id = ? AND product_id = ?",
                Long.class, storeId, productId(productCode));
    }

    private long productId(String productCode) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private ReserveMessage reserve(long orderId, long storeId, long productId, int quantity) {
        return new ReserveMessage(UUID.randomUUID().toString(), NOW, orderId, storeId, productId, quantity);
    }

    private void handle(BaseMessage message) {
        inventoryCommandService.handle(message);
    }

    private Map<String, Object> disposeOutbox(long disposalId) {
        return jdbcTemplate.queryForMap("""
                SELECT message_type, queue_name, group_id, payload
                FROM outbox
                WHERE message_type = 'DISPOSE'
                  AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.disposalId')) = ?
                """, String.valueOf(disposalId));
    }

    /** API가 outbox에 저장한 DISPOSE 쪽지를 그대로 꺼낸다 (SQS를 거쳐 돌아온 것과 같다) */
    private DisposeMessage disposeMessage(long disposalId) {
        return (DisposeMessage) MessageJson.fromJson((String) disposeOutbox(disposalId).get("payload"));
    }

    private int lotQuantity(long lotId) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory_lot WHERE id = ?", Integer.class, lotId);
    }

    private String disposalStatus(long disposalId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM disposal WHERE id = ?", String.class, disposalId);
    }

    private int disposalCount(long lotId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disposal WHERE lot_id = ?", Integer.class, lotId);
    }
}
