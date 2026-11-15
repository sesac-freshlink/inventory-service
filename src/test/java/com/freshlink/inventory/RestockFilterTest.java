package com.freshlink.inventory;

import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.inventory.common.web.XStoreIdFilter;
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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 점주 홈 "재입고 필요" = 품절 또는 부족(11/15). 품절 상품은 부족에도 들어가서 둘을 더하면 두 번 세어졌다.
 * 안전재고 0인 품절 상품은 부족이 아니어도 재입고 필요다. 점포·상품 ID는 741x.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class RestockFilterTest {

    static final String FAR = "2027-12-31T23:59:59+09:00";
    /** 이미 지난 기한: 구매 가능 수량에서 빠져 품절이 된다 */
    static final String PAST = "2026-10-24T23:59:59+09:00";

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
    void 재입고_필요는_품절_또는_부족이고_한_번씩만_센다() throws Exception {
        long storeId = 7411L;
        long soldOutLow = product("P7411", "재입고 품절");       // 품절, 안전재고 5 → 부족이기도 하다
        long soldOutNoSafety = product("P7412", "재입고 품절 안전재고0");
        long low = product("P7413", "재입고 부족");             // 2개 < 안전재고 5
        long enough = product("P7414", "재입고 넉넉");
        inbound(storeId, "P7411", 3, PAST);
        inbound(storeId, "P7412", 3, PAST);
        inbound(storeId, "P7413", 2, FAR);
        inbound(storeId, "P7414", 30, FAR);
        safetyStock(storeId, soldOutNoSafety, 0);

        mockMvc.perform(get("/owner/inventory").param("status", "RESTOCK").param("size", "100")
                        .header("X-Store-Id", String.valueOf(storeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[*].productId")
                        .value(containsInAnyOrder((int) soldOutLow, (int) soldOutNoSafety, (int) low)));
        // 넉넉한 상품은 빠진다
        mockMvc.perform(get("/owner/inventory").param("status", "RESTOCK").param("q", "넉넉")
                        .header("X-Store-Id", String.valueOf(storeId)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    private void safetyStock(long storeId, long productId, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO safety_stock (store_id, product_id, quantity, created_at, updated_at)
                VALUES (?, ?, ?, NOW(6), NOW(6))
                """, storeId, productId, quantity);
    }

    private void inbound(long storeId, String productCode, int quantity, String expiry) {
        inventoryCommandService.handle(new InboundMessage(UUID.randomUUID().toString(), NOW, storeId,
                "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(expiry)))));
    }

    private long product(String productCode, String name) {
        jdbcTemplate.update("INSERT INTO product (product_code, name, category, price) VALUES (?, ?, ?, ?)",
                productCode, name, "재입고시험", 1000L);
        return jdbcTemplate.queryForObject("SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }
}
