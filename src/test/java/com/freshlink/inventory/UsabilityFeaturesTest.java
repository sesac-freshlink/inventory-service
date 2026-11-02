package com.freshlink.inventory;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.DisposeMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.common.message.command.ReturnMessage;
import com.freshlink.inventory.common.web.XStoreIdFilter;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사용성 추가 기능: 경과 lot 한 번에 폐기, 재고 소진 예상(점주 목록), 고객 목록의 유통기한, 추천상품 여러 개 한 번에.
 * 점포·상품·주문 ID는 57xx. 지금 시각은 FixedClockConfiguration.NOW = 2026-10-25 12:00 KST.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class UsabilityFeaturesTest {

    static final String FAR = "2027-12-31T23:59:59+09:00";
    static final String PAST = "2026-10-24T23:59:59+09:00";
    static final String USER_ID = "11111111-2222-3333-4444-555555555555";

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    XStoreIdFilter xStoreIdFilter;

    @Autowired
    InventoryCommandService inventoryCommandService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(xStoreIdFilter)
                .build();
    }

    @Test
    void 경과_lot만_폐기_가능_수량_전부를_lot마다_요청하고_다시_눌러도_같은_수량을_또_요청하지_않는다() throws Exception {
        long storeId = 5701L;
        long expiredA = product("P5701", "폐기 우유");
        long expiredB = product("P5702", "폐기 빵");
        long fresh = product("P5703", "정상 라면");
        inbound(storeId, "P5701", 3, PAST);
        inbound(storeId, "P5702", 2, "2026-10-25T11:00:00+09:00");
        inbound(storeId, "P5703", 5, FAR);

        expiredDisposal(storeId).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.lotCount").value(2))
                .andExpect(jsonPath("$.quantity").value(5))
                .andExpect(jsonPath("$.items[*].lotId").value(contains(
                        (int) lotOf(storeId, expiredA), (int) lotOf(storeId, expiredB))))
                .andExpect(jsonPath("$.items[*].quantity").value(contains(3, 2)));

        // 처리 전(REQUESTED) 수량을 빼므로 두 번째 요청은 아무것도 만들지 않는다
        expiredDisposal(storeId).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.lotCount").value(0))
                .andExpect(jsonPath("$.items").value(empty()));
        assertThat(disposalCount(storeId)).isEqualTo(2);
        assertThat(disposalCount(storeId, fresh)).isZero();

        // 저장된 DISPOSE 쪽지를 처리하면 단건 폐기처럼 수량이 0이 된다
        disposeOutbox(storeId).forEach(this::handle);
        assertThat(lotQuantity(lotOf(storeId, expiredA))).isZero();
        assertThat(lotQuantity(lotOf(storeId, expiredB))).isZero();
        assertThat(lotQuantity(lotOf(storeId, fresh))).isEqualTo(5);
    }

    @Test
    void 버릴_경과_lot이_없으면_빈_결과로_202() throws Exception {
        long storeId = 5702L;
        product("P5704", "정상만");
        inbound(storeId, "P5704", 4, FAR);

        expiredDisposal(storeId).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.lotCount").value(0))
                .andExpect(jsonPath("$.quantity").value(0));
    }

    @Test
    void 점주_재고_목록에_최근_7일_하루_평균_순판매와_남은_일수가_붙는다() throws Exception {
        long storeId = 5703L;
        long selling = product("P5705", "소진 예상 라면");
        long idle = product("P5706", "판매 없는 생수");
        inbound(storeId, "P5705", 30, FAR);
        inbound(storeId, "P5706", 10, FAR);
        sell(5701L, storeId, selling, 10);
        sell(5702L, storeId, selling, 6);
        handle(ReturnMessage.of(5702L, storeId)); // 환불 6개는 판매에서 빠진다 → 순판매 10

        owner(get("/owner/inventory").param("size", "100"), storeId).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.productId == " + selling + ")].dailySales").value(contains(1.4)))
                // 구매 가능 20개 ÷ 하루 10/7개 = 14일
                .andExpect(jsonPath("$.items[?(@.productId == " + selling + ")].daysOfStock").value(contains(14)))
                .andExpect(jsonPath("$.items[?(@.productId == " + idle + ")].dailySales").value(contains(0.0)))
                .andExpect(jsonPath("$.items[?(@.productId == " + idle + ")].daysOfStock")
                        .value(contains(nullValue())));
    }

    @Test
    void 고객_상품_목록에_먼저_받을_상품의_유통기한이_오고_품절이면_null() throws Exception {
        long storeId = 5704L;
        long milk = product("P5707", "기한 우유");
        long soldOut = product("P5708", "기한 품절");
        inbound(storeId, "P5707", 2, "2026-10-27T23:59:59+09:00");
        inbound(storeId, "P5707", 5, "2026-11-03T23:59:59+09:00");
        inbound(storeId, "P5708", 1, FAR);
        sell(5703L, storeId, soldOut, 1);

        mockMvc.perform(get("/stores/" + storeId + "/products").param("q", "기한"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.productId == " + milk + ")].nearestExpiry")
                        .value(contains("2026-10-27T23:59:59+09:00")))
                .andExpect(jsonPath("$[?(@.productId == " + soldOut + ")].nearestExpiry")
                        .value(contains(nullValue())));
    }

    @Test
    void 추천상품을_여러_개_한_번에_켜고_끈다() throws Exception {
        long storeId = 5705L;
        long a = product("P5709", "한번에 가");
        long b = product("P5710", "한번에 나");

        bulk(storeId, "[" + b + "," + a + "]", true).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].productId").value(contains((int) a, (int) b)))
                .andExpect(jsonPath("$[*].recommended").value(contains(true, true)));
        assertThat(recommendedIds(storeId)).containsExactlyInAnyOrder(a, b);

        // 다시 보내도 결과가 같다
        bulk(storeId, "[" + a + "]", true).andExpect(status().isOk());
        assertThat(recommendedIds(storeId)).containsExactlyInAnyOrder(a, b);

        bulk(storeId, "[" + a + "]", false).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recommended").value(false));
        assertThat(recommendedIds(storeId)).containsExactly(b);
    }

    @Test
    void 없는_상품이_섞이면_아무것도_바꾸지_않고_404_빈_목록은_400() throws Exception {
        long storeId = 5706L;
        long a = product("P5711", "한번에 다");

        bulk(storeId, "[" + a + ",999999]", true).andExpect(status().isNotFound());
        assertThat(recommendedIds(storeId)).isEmpty();

        bulk(storeId, "[]", true).andExpect(status().isBadRequest());
    }

    private ResultActions expiredDisposal(long storeId) throws Exception {
        return mockMvc.perform(post("/owner/inventory/disposals/expired")
                .header("X-Store-Id", String.valueOf(storeId))
                .header("X-User-Id", USER_ID));
    }

    private ResultActions bulk(long storeId, String productIds, boolean recommended) throws Exception {
        return owner(put("/owner/inventory/recommendations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productIds\":" + productIds + ",\"recommended\":" + recommended + "}"), storeId);
    }

    private ResultActions owner(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, long storeId)
            throws Exception {
        return mockMvc.perform(request.header("X-Store-Id", String.valueOf(storeId)));
    }

    private List<DisposeMessage> disposeOutbox(long storeId) {
        return jdbcTemplate.queryForList("""
                        SELECT payload FROM outbox
                        WHERE message_type = 'DISPOSE' AND JSON_EXTRACT(payload, '$.storeId') = ?
                        ORDER BY id
                        """, String.class, storeId).stream()
                .map(payload -> (DisposeMessage) MessageJson.fromJson(payload))
                .toList();
    }

    private List<Long> recommendedIds(long storeId) {
        return jdbcTemplate.queryForList(
                "SELECT product_id FROM product_recommendation WHERE store_id = ?", Long.class, storeId);
    }

    private int disposalCount(long storeId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM disposal WHERE store_id = ?", Integer.class, storeId);
    }

    private int disposalCount(long storeId, long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disposal WHERE lot_id = ?", Integer.class, lotOf(storeId, productId));
    }

    private long lotOf(long storeId, long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_lot WHERE store_id = ? AND product_id = ? ORDER BY id LIMIT 1",
                Long.class, storeId, productId);
    }

    private int lotQuantity(long lotId) {
        return jdbcTemplate.queryForObject("SELECT quantity FROM inventory_lot WHERE id = ?", Integer.class, lotId);
    }

    private void sell(long orderId, long storeId, long productId, int quantity) {
        handle(new ReserveMessage(newId(), NOW, orderId, storeId, productId, quantity));
        handle(new ConfirmMessage(newId(), NOW, orderId, storeId));
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
                productCode, name, "사용성시험", 1000L);
        return jdbcTemplate.queryForObject("SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
