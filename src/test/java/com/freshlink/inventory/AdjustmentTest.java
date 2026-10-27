package com.freshlink.inventory;

import com.freshlink.common.error.BusinessException;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.inventory.common.web.XStoreIdFilter;
import com.freshlink.inventory.domain.adjustment.AdjustmentReason;
import com.freshlink.inventory.domain.adjustment.AdjustmentRequest;
import com.freshlink.inventory.domain.adjustment.AdjustmentService;
import com.freshlink.inventory.domain.inventory.CommandResult;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import com.freshlink.inventory.domain.inventory.InventoryLotRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-14 재고 수동 조정과 낙관적 락. 지금 시각은 2026-10-25 12:00 KST.
 * 조정은 쪽지를 거치지 않으므로 같은 lot을 고치는 쪽지 처리와 겹칠 수 있다. 겹침은 두 트랜잭션을 직접 엇갈리게 만들어 본다.
 * 점포 4401~4499, 상품 P44xx, 주문 44xx를 쓴다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class AdjustmentTest {

    static final String NORMAL = "2027-12-31T23:59:59+09:00";
    static final String USER_ID = "11111111-2222-3333-4444-555555555555";

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    XStoreIdFilter xStoreIdFilter;

    @Autowired
    InventoryCommandService inventoryCommandService;

    @Autowired
    AdjustmentService adjustmentService;

    @Autowired
    InventoryLotRepository inventoryLotRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;

    /** 바깥 트랜잭션. 조정(또는 쪽지 처리)이 lot을 읽고 고치는 쪽 */
    TransactionTemplate outer;

    /** 바깥 트랜잭션을 잠시 멈추고 따로 커밋하는 트랜잭션. "그 사이 다른 쪽이 먼저 커밋했다"를 만든다 */
    TransactionTemplate meanwhile;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(xStoreIdFilter)
                .build();
        outer = new TransactionTemplate(transactionManager);
        meanwhile = new TransactionTemplate(transactionManager);
        meanwhile.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void 실사로_줄이면_lot_수량_조정_기록_원장이_한_번에_남는다() throws Exception {
        long storeId = 4401L;
        product("P4401");
        long lotId = inbound(storeId, "P4401", 10);
        long versionBefore = version(lotId);

        String body = adjust("4401", lotId, -2, "COUNT", "진열대 실사")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.delta").value(-2))
                .andExpect(jsonPath("$.quantityBefore").value(10))
                .andExpect(jsonPath("$.quantityAfter").value(8))
                .andReturn().getResponse().getContentAsString();
        long adjustmentId = ((Number) JsonPath.read(body, "$.adjustmentId")).longValue();

        assertThat(lotQuantity(lotId)).isEqualTo(8);
        assertThat(version(lotId)).isEqualTo(versionBefore + 1);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT type, quantity, ref_id FROM ledger WHERE lot_id = ? AND type = 'ADJUST'", lotId))
                .containsEntry("quantity", -2)
                .containsEntry("ref_id", String.valueOf(adjustmentId));
        assertThat(jdbcTemplate.queryForMap(
                "SELECT delta, quantity_before, quantity_after, reason, memo, adjusted_by FROM adjustment WHERE id = ?",
                adjustmentId))
                .containsEntry("delta", -2)
                .containsEntry("reason", "COUNT")
                .containsEntry("memo", "진열대 실사")
                .containsEntry("adjusted_by", USER_ID);
        // 원장 합계 = lot 수량 (입고 +10, 조정 −2)
        assertThat(ledgerSum(lotId)).isEqualTo(8);
    }

    @Test
    void 찾은_재고는_늘릴_수_있다() throws Exception {
        product("P4402");
        long lotId = inbound(4402L, "P4402", 3);

        adjust("4402", lotId, 4, "OTHER", null).andExpect(status().isCreated());

        assertThat(lotQuantity(lotId)).isEqualTo(7);
        assertThat(ledgerSum(lotId)).isEqualTo(7);
    }

    @Test
    void 예약된_수량보다_적게_줄이면_409이고_아무것도_바뀌지_않는다() throws Exception {
        long storeId = 4403L;
        long productId = product("P4403");
        long lotId = inbound(storeId, "P4403", 5);
        inventoryCommandService.handle(reserve(4403L, storeId, productId, 3));

        // 실물 5, 예약 3 → 2개까지만 줄일 수 있다
        adjust("4403", lotId, -3, "LOSS", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BELOW_RESERVED"));

        assertThat(lotQuantity(lotId)).isEqualTo(5);
        assertThat(adjustmentCount(lotId)).isZero();

        adjust("4403", lotId, -2, "LOSS", null).andExpect(status().isCreated());
        assertThat(lotQuantity(lotId)).isEqualTo(3);
    }

    @Test
    void 다른_점포_lot은_403_없는_lot은_404_증감_0은_400이다() throws Exception {
        product("P4404");
        long lotId = inbound(4404L, "P4404", 5);

        adjust("4405", lotId, -1, "COUNT", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOT_NOT_IN_STORE"));
        adjust("4404", 999_999L, -1, "COUNT", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOT_NOT_FOUND"));
        adjust("4404", lotId, 0, "COUNT", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));

        assertThat(lotQuantity(lotId)).isEqualTo(5);
    }

    @Test
    void 조정이_lot을_읽은_뒤_쪽지_처리가_먼저_커밋하면_조정은_LOT_CHANGED로_끝나고_쪽지_결과는_남는다() {
        long storeId = 4406L;
        long productId = product("P4406");
        long lotId = inbound(storeId, "P4406", 10);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            // 1. 조정 트랜잭션이 lot을 읽는다 (수량 10, version n)
            inventoryLotRepository.findById(lotId).orElseThrow();
            // 2. 그 사이 RESERVE 쪽지가 같은 lot에 예약 2를 잡고 커밋한다 (version n+1)
            meanwhile.executeWithoutResult(s ->
                    inventoryCommandService.handle(reserve(4406L, storeId, productId, 2)));
            // 3. 조정은 1에서 읽은 lot으로 UPDATE ... WHERE version = n → 0행
            adjustmentService.adjust(storeId, USER_ID, new AdjustmentRequest(lotId, -1, AdjustmentReason.COUNT, null));
        }))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo("LOT_CHANGED"));

        // 쪽지가 한 예약은 그대로, 조정은 하나도 남지 않는다
        assertThat(lotQuantity(lotId)).isEqualTo(10);
        assertThat(reservedQuantity(lotId)).isEqualTo(2);
        assertThat(adjustmentCount(lotId)).isZero();
        assertThat(ledgerSum(lotId)).isEqualTo(10);
    }

    @Test
    void 쪽지_처리가_lot을_읽은_뒤_조정이_먼저_커밋하면_쪽지_트랜잭션은_통째로_되돌려지고_다시_받으면_처리된다() {
        long storeId = 4407L;
        long productId = product("P4407");
        long lotId = inbound(storeId, "P4407", 10);
        ReserveMessage message = reserve(4407L, storeId, productId, 2);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            // 1. 쪽지 처리 트랜잭션이 lot을 읽는다
            inventoryLotRepository.findById(lotId).orElseThrow();
            // 2. 그 사이 점주 조정(−3)이 커밋한다
            meanwhile.executeWithoutResult(s -> adjustmentService.adjust(
                    storeId, USER_ID, new AdjustmentRequest(lotId, -3, AdjustmentReason.DAMAGE, null)));
            // 3. 쪽지 처리는 읽은 값(10)으로 예약하고 커밋하려다 0행 → 예외
            inventoryCommandService.handle(message);
        })).isInstanceOf(OptimisticLockingFailureException.class);

        // processed_message 기록·예약·결과 쪽지까지 같은 트랜잭션이라 모두 없다. 조정만 남았다
        assertThat(processedCount(message.messageId())).isZero();
        assertThat(reservationCount(4407L)).isZero();
        assertThat(lotQuantity(lotId)).isEqualTo(7);
        assertThat(reservedQuantity(lotId)).isZero();

        // SQS가 같은 쪽지를 다시 보내면 바뀐 수량(7)으로 처리된다
        assertThat(inventoryCommandService.handle(message)).isEqualTo(CommandResult.PROCESSED);
        assertThat(reservedQuantity(lotId)).isEqualTo(2);
        assertThat(processedCount(message.messageId())).isEqualTo(1);
    }

    @Test
    void 조정_이력은_최신순으로_상품명과_함께_준다() throws Exception {
        long storeId = 4408L;
        product("P4408");
        long lotId = inbound(storeId, "P4408", 10);
        adjust("4408", lotId, -1, "DAMAGE", "포장 찢어짐").andExpect(status().isCreated());
        adjust("4408", lotId, 2, "COUNT", null).andExpect(status().isCreated());

        mockMvc.perform(get("/owner/inventory/adjustments").header("X-Store-Id", "4408"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].delta").value(2))
                .andExpect(jsonPath("$[0].quantityBefore").value(9))
                .andExpect(jsonPath("$[0].quantityAfter").value(11))
                .andExpect(jsonPath("$[0].productName").value("조정 P4408"))
                .andExpect(jsonPath("$[1].reason").value("DAMAGE"))
                .andExpect(jsonPath("$[1].memo").value("포장 찢어짐"));
    }

    private ResultActions adjust(String storeId, long lotId, int delta, String reason, String memo) throws Exception {
        String memoJson = memo == null ? "" : ",\"memo\":\"" + memo + "\"";
        return mockMvc.perform(post("/owner/inventory/adjustments")
                .header("X-Store-Id", storeId)
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lotId\":" + lotId + ",\"delta\":" + delta + ",\"reason\":\"" + reason + "\"" + memoJson + "}"));
    }

    private long product(String productCode) {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, "조정 " + productCode, "테스트", 1500L);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private long inbound(long storeId, String productCode, int quantity) {
        inventoryCommandService.handle(new InboundMessage(
                UUID.randomUUID().toString(),
                NOW,
                storeId,
                "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(NORMAL)))
        ));
        return jdbcTemplate.queryForObject("""
                SELECT lot.id FROM inventory_lot lot JOIN product p ON p.id = lot.product_id
                WHERE lot.store_id = ? AND p.product_code = ?
                """, Long.class, storeId, productCode);
    }

    private ReserveMessage reserve(long orderId, long storeId, long productId, int quantity) {
        return new ReserveMessage(UUID.randomUUID().toString(), NOW, orderId, storeId, productId, quantity);
    }

    private int lotQuantity(long lotId) {
        return jdbcTemplate.queryForObject("SELECT quantity FROM inventory_lot WHERE id = ?", Integer.class, lotId);
    }

    private int reservedQuantity(long lotId) {
        return jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM inventory_lot WHERE id = ?", Integer.class, lotId);
    }

    private long version(long lotId) {
        return jdbcTemplate.queryForObject("SELECT version FROM inventory_lot WHERE id = ?", Long.class, lotId);
    }

    private int ledgerSum(long lotId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM ledger WHERE lot_id = ?", Integer.class, lotId);
    }

    private int adjustmentCount(long lotId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adjustment WHERE lot_id = ?", Integer.class, lotId);
    }

    private int reservationCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE order_id = ?", Integer.class, orderId);
    }

    private int processedCount(String messageId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_message WHERE message_id = ?", Integer.class, messageId);
    }
}
