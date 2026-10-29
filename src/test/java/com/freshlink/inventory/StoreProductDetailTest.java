package com.freshlink.inventory;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U-10 고객 상품 상세. 고객용 재고 조회 표에서 구매 가능 수량과, 먼저 받게 될 상품의 유통기한
 * (남은 재고가 있는 미경과 lot 중 가장 빠른 기한 = 조회 표의 valid_until, 예약은 FEFO)을 준다.
 * 지금 시각은 2026-10-25 12:00 KST. 점포 5101~5199, 상품 P51xx, 주문 51xx를 쓴다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class StoreProductDetailTest {

    static final String FRESH = "2027-12-31T23:59:59+09:00";
    static final String TONIGHT = "2026-10-25T23:59:59+09:00";

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
    void 상품_정보와_구매_가능_수량과_먼저_받게_될_상품의_유통기한을_준다() throws Exception {
        long storeId = 5101L;
        long productId = product("P5101", "상세 흰 우유 500ml");
        handle(inbound(storeId, "P5101", 2, TONIGHT));
        handle(inbound(storeId, "P5101", 5, FRESH));

        detail(storeId, productId)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.productId").value(productId))
                .andExpect(jsonPath("$.name").value("상세 흰 우유 500ml"))
                .andExpect(jsonPath("$.category").value("테스트"))
                .andExpect(jsonPath("$.price").value(1500))
                .andExpect(jsonPath("$.availableQuantity").value(7))
                .andExpect(jsonPath("$.soldOut").value(false))
                // 예약은 기한 빠른 것부터(FEFO)라 오늘까지 lot을 먼저 받는다
                .andExpect(jsonPath("$.nearestExpiry").value("2026-10-25T23:59:59+09:00"));
    }

    @Test
    void 기한_빠른_lot이_예약으로_다_잡혔으면_그다음_lot의_기한을_준다() throws Exception {
        long storeId = 5102L;
        long productId = product("P5102", "상세 예약된 라면");
        handle(inbound(storeId, "P5102", 2, TONIGHT));
        handle(inbound(storeId, "P5102", 5, FRESH));
        handle(new ReserveMessage(newId(), NOW, 5101L, storeId, productId, 2));

        detail(storeId, productId)
                .andExpect(jsonPath("$.availableQuantity").value(5))
                .andExpect(jsonPath("$.nearestExpiry").value("2027-12-31T23:59:59+09:00"));
    }

    @Test
    void 입고가_없는_상품은_품절이고_유통기한이_없다() throws Exception {
        long productId = product("P5103", "상세 입고 전 빵");

        detail(5103L, productId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(0))
                .andExpect(jsonPath("$.soldOut").value(true))
                .andExpect(jsonPath("$.nearestExpiry").value(nullValue()));
    }

    @Test
    void 값이_맞는_시각이_지난_조회표_행은_품절이고_유통기한을_보여_주지_않는다() throws Exception {
        long storeId = 5104L;
        long productId = product("P5104", "상세 시각 지난 김밥");
        handle(inbound(storeId, "P5104", 3, FRESH));
        jdbcTemplate.update("UPDATE store_product_stock SET valid_until = ? WHERE store_id = ? AND product_id = ?",
                NOW.toLocalDateTime(), storeId, productId);

        detail(storeId, productId)
                .andExpect(jsonPath("$.availableQuantity").value(0))
                .andExpect(jsonPath("$.soldOut").value(true))
                .andExpect(jsonPath("$.nearestExpiry").value(nullValue()));
    }

    @Test
    void 없는_상품이면_404다() throws Exception {
        detail(5105L, 999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    private ResultActions detail(long storeId, long productId) throws Exception {
        return mockMvc.perform(get("/stores/" + storeId + "/products/" + productId));
    }

    private void handle(BaseMessage message) {
        inventoryCommandService.handle(message);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static InboundMessage inbound(long storeId, String productCode, int quantity, String expiryDate) {
        return new InboundMessage(newId(), NOW, storeId, "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(expiryDate))));
    }

    private long product(String productCode, String name) {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, name, "테스트", 1500L);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }
}
