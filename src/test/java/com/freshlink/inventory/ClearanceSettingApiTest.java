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
import java.util.Map;
import java.util.UUID;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 점주가 분류마다 정하는 마감세일 설정(적용 여부·할인율·판매 시간)과 그 설정이 예약·고객 상세에 반영되는지.
 * 지금 시각은 2026-10-25 12:00 KST. 점포 6301~, 상품 P63xx, 주문 63xx, 분류 "설정테스트*"를 쓴다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class ClearanceSettingApiTest {

    static final String TONIGHT = "2026-10-25T23:59:59+09:00";
    static final String FRESH = "2026-11-05T23:59:59+09:00";

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    InventoryCommandService inventoryCommandService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void 정하지_않은_분류는_기본값이고_저장하면_customized다() throws Exception {
        long storeId = 6301L;
        product("P6301", "설정테스트가");

        mockMvc.perform(get("/owner/inventory/clearance-settings").header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.category == '설정테스트가')].enabled").value(true))
                .andExpect(jsonPath("$[?(@.category == '설정테스트가')].discountPercent").value(30))
                .andExpect(jsonPath("$[?(@.category == '설정테스트가')].saleStartHour").value(6))
                .andExpect(jsonPath("$[?(@.category == '설정테스트가')].saleEndHour").value(23))
                .andExpect(jsonPath("$[?(@.category == '설정테스트가')].customized").value(false));

        save(storeId, "설정테스트가", false, 50, 8, 20)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.discountPercent").value(50))
                .andExpect(jsonPath("$.customized").value(true));
        // 같은 값으로 다시 보내도 결과가 같다(행은 하나)
        save(storeId, "설정테스트가", false, 50, 8, 20).andExpect(status().isOk());

        mockMvc.perform(get("/owner/inventory/clearance-settings").header("X-Store-Id", storeId))
                .andExpect(jsonPath("$[?(@.category == '설정테스트가')].saleStartHour").value(8))
                .andExpect(jsonPath("$[?(@.category == '설정테스트가')].customized").value(true));
        // 다른 점포는 그대로 기본값이다
        mockMvc.perform(get("/owner/inventory/clearance-settings").header("X-Store-Id", 6399L))
                .andExpect(jsonPath("$[?(@.category == '설정테스트가')].customized").value(false));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM clearance_setting WHERE store_id = ?", Integer.class, storeId)).isEqualTo(1);
    }

    @Test
    void 범위를_벗어나거나_시작이_끝보다_늦거나_없는_분류면_거절한다() throws Exception {
        long storeId = 6302L;
        product("P6302", "설정테스트나");

        save(storeId, "설정테스트나", true, 91, 6, 23).andExpect(status().isBadRequest());
        save(storeId, "설정테스트나", true, 9, 6, 23).andExpect(status().isBadRequest());
        save(storeId, "설정테스트나", true, 30, 6, 25).andExpect(status().isBadRequest());
        save(storeId, "설정테스트나", true, 30, 12, 12)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CLEARANCE_SETTING"));
        save(storeId, "없는분류", true, 30, 6, 23)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM clearance_setting WHERE store_id = ?", Integer.class, storeId)).isZero();
    }

    @Test
    void 정한_할인율로_마감세일_단가를_잡고_고객_상세에도_같은_값을_준다() throws Exception {
        long storeId = 6303L;
        long productId = product("P6303", "설정테스트다");
        handle(inbound(storeId, "P6303", 2, TONIGHT));
        handle(inbound(storeId, "P6303", 5, FRESH));
        save(storeId, "설정테스트다", true, 90, 6, 23).andExpect(status().isOk());

        mockMvc.perform(get("/stores/{storeId}/products/{productId}", storeId, productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clearanceQuantity").value(2))
                .andExpect(jsonPath("$.clearancePrice").value(150))   // 1500원의 10%
                .andExpect(jsonPath("$.clearanceDiscountPercent").value(90));

        handle(reserve(6303L, storeId, productId, 2, 1));

        ReservedMessage reserved = reserved(6303L);
        ReservedLot clearance = reserved.lots().getFirst();
        assertThat(clearance.clearance()).isTrue();
        assertThat(clearance.unitPrice()).isEqualTo(150L);
    }

    @Test
    void 마감세일을_끈_분류는_24시간_안_lot도_기본가로_팔고_마감세일_요청은_CLEARANCE_SOLD_OUT이다() throws Exception {
        long storeId = 6304L;
        long productId = product("P6304", "설정테스트라");
        handle(inbound(storeId, "P6304", 3, TONIGHT));
        save(storeId, "설정테스트라", false, 30, 6, 23).andExpect(status().isOk());

        mockMvc.perform(get("/stores/{storeId}/products/{productId}", storeId, productId))
                .andExpect(jsonPath("$.availableQuantity").value(3))
                .andExpect(jsonPath("$.clearanceQuantity").value(0))
                .andExpect(jsonPath("$.clearancePrice").value(nullValue()))
                .andExpect(jsonPath("$.clearanceDiscountPercent").value(nullValue()));

        handle(reserve(6304L, storeId, productId, 1, 1));
        ReserveFailedMessage failed = failed(6304L);
        assertThat(failed.reason()).isEqualTo(ReserveFailReason.CLEARANCE_SOLD_OUT);
        assertThat(failed.clearanceAvailableQuantity()).isZero();

        handle(reserve(6314L, storeId, productId, 2, 0));
        ReservedMessage reserved = reserved(6314L);
        assertThat(reserved.lots()).hasSize(1);
        assertThat(reserved.lots().getFirst().clearance()).isFalse();
        assertThat(reserved.lots().getFirst().unitPrice()).isNull();
    }

    @Test
    void 정한_판매_시간_밖이면_마감세일_수량이_0이고_예약도_거절한다() throws Exception {
        long storeId = 6305L;
        long productId = product("P6305", "설정테스트마");
        handle(inbound(storeId, "P6305", 2, TONIGHT));
        // 지금은 12시라 13~24시 판매면 시간 밖이다
        save(storeId, "설정테스트마", true, 30, 13, 24).andExpect(status().isOk());

        mockMvc.perform(get("/stores/{storeId}/products/{productId}", storeId, productId))
                .andExpect(jsonPath("$.availableQuantity").value(0))
                .andExpect(jsonPath("$.clearanceQuantity").value(0))
                .andExpect(jsonPath("$.soldOut").value(true));

        handle(reserve(6305L, storeId, productId, 1, 1));
        assertThat(failed(6305L).reason()).isEqualTo(ReserveFailReason.CLEARANCE_SOLD_OUT);
    }

    @Test
    void 다른_점포의_설정은_섞이지_않는다() throws Exception {
        long productId = product("P6306", "설정테스트바");
        handle(inbound(6306L, "P6306", 2, TONIGHT));
        handle(inbound(6307L, "P6306", 2, TONIGHT));
        save(6306L, "설정테스트바", false, 30, 6, 23).andExpect(status().isOk());

        handle(reserve(6307L, 6307L, productId, 1, 1));

        ReservedLot lot = reserved(6307L).lots().getFirst();
        assertThat(lot.clearance()).isTrue();
        assertThat(lot.unitPrice()).isEqualTo(1050L);
        // 주변 점포 재고: 끈 점포(6306)는 2개 전부 기본, 기본값 점포(6307)는 마감세일 남은 1개
        mockMvc.perform(get("/products/{productId}/stores", productId))
                .andExpect(jsonPath("$[?(@.storeId == 6306)].availableQuantity").value(2))
                .andExpect(jsonPath("$[?(@.storeId == 6307)].availableQuantity").value(1));
    }

    private ResultActions save(long storeId, String category, boolean enabled, int discount, int start, int end)
            throws Exception {
        return mockMvc.perform(put("/owner/inventory/clearance-settings")
                .header("X-Store-Id", storeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"category": "%s", "enabled": %s, "discountPercent": %d, "saleStartHour": %d, "saleEndHour": %d}
                        """.formatted(category, enabled, discount, start, end)));
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

    private long product(String productCode, String category) {
        jdbcTemplate.update("INSERT INTO product (product_code, name, category, price) VALUES (?, ?, ?, ?)",
                productCode, "마감세일 설정 " + productCode, category, 1500L);
        return jdbcTemplate.queryForObject("SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
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
