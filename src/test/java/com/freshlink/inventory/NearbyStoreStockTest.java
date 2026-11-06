package com.freshlink.inventory;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.command.ConfirmMessage;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주변 점포 재고: 상품 하나를 지금 살 수 있는 점포와 수량(고객 상품 목록과 같은 조회 표). 점포·상품·주문 ID는 58xx.
 * 지금 시각은 FixedClockConfiguration.NOW = 2026-10-25 12:00 KST.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class NearbyStoreStockTest {

    static final String FAR = "2027-12-31T23:59:59+09:00";

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
    void 지금_살_수_있는_점포만_많이_남은_순으로_주고_품절_경과뿐인_점포는_뺀다() throws Exception {
        long productId = product("P5801", "주변 재고 우유");
        inbound(5801L, "P5801", 2, FAR);
        inbound(5802L, "P5801", 5, "2026-10-28T23:59:59+09:00");
        inbound(5802L, "P5801", 1, FAR);
        // 다 팔린 점포
        inbound(5803L, "P5801", 1, FAR);
        sell(5801L, 5803L, productId, 1);
        // 기한이 지난 lot만 있는 점포
        inbound(5804L, "P5801", 3, "2026-10-24T23:59:59+09:00");

        mockMvc.perform(get("/products/" + productId + "/stores"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[*].storeId").value(contains(5802, 5801)))
                .andExpect(jsonPath("$[*].availableQuantity").value(contains(6, 2)))
                // 먼저 받게 될 상품의 유통기한(가장 빠른 미경과 lot)
                .andExpect(jsonPath("$[0].nearestExpiry").value("2026-10-28T23:59:59+09:00"));
    }

    @Test
    void 어디에도_없으면_빈_배열_없는_상품은_404() throws Exception {
        long productId = product("P5802", "주변 재고 없음");

        mockMvc.perform(get("/products/" + productId + "/stores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/products/999999/stores"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
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
                productCode, name, "주변재고시험", 1000L);
        return jdbcTemplate.queryForObject("SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
