package com.freshlink.inventory;

import com.freshlink.common.message.BaseMessage;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 점주 화면이 재고 목록을 한 번 더 부르지 않게(11/15 경우 요청) 목록 응답에 붙인 칸:
 * 재고 목록의 마감세일 수량, 추천·선착순 설정의 재고(판매 가능·안전재고·부족).
 * 점포·상품 ID는 74xx. 지금 시각은 FixedClockConfiguration.NOW = 2026-10-25 12:00 KST(마감세일 판매 시간 안).
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class OwnerListStockFieldsTest {

    static final String FAR = "2027-12-31T23:59:59+09:00";
    /** 24시간 안에 끝나는 기한: 마감세일 대상 */
    static final String TONIGHT = "2026-10-25T23:59:59+09:00";

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
    void 재고_목록에_고객_목록과_같은_마감세일_수량이_붙는다() throws Exception {
        long storeId = 7401L;
        long sale = product("P7401", "마감 김밥");
        long normal = product("P7402", "넉넉 라면");
        inbound(storeId, "P7401", 3, TONIGHT);
        inbound(storeId, "P7401", 5, FAR);
        inbound(storeId, "P7402", 10, FAR);

        owner(get("/owner/inventory").param("size", "100"), storeId).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.productId == " + sale + ")].clearanceQuantity").value(contains(3)))
                .andExpect(jsonPath("$.items[?(@.productId == " + normal + ")].clearanceQuantity").value(contains(0)));
        mockMvc.perform(get("/stores/" + storeId + "/products"))
                .andExpect(jsonPath("$[?(@.productId == " + sale + ")].clearanceQuantity").value(contains(3)));
    }

    @Test
    void 추천_설정에_판매_가능_안전재고_부족이_붙는다() throws Exception {
        long storeId = 7402L;
        long few = product("P7403", "조금 남은 우유");
        long many = product("P7404", "많이 남은 우유");
        inbound(storeId, "P7403", 2, FAR);
        inbound(storeId, "P7404", 40, FAR);
        safetyStock(storeId, few, 5);

        owner(get("/owner/inventory/recommendations"), storeId).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.productId == " + few + ")].stock.availableQuantity").value(contains(2)))
                .andExpect(jsonPath("$[?(@.productId == " + few + ")].stock.safetyStock").value(contains(5)))
                .andExpect(jsonPath("$[?(@.productId == " + few + ")].stock.lowStock").value(contains(true)))
                .andExpect(jsonPath("$[?(@.productId == " + many + ")].stock.availableQuantity").value(contains(40)))
                .andExpect(jsonPath("$[?(@.productId == " + many + ")].stock.lowStock").value(contains(false)));
    }

    @Test
    void 선착순_설정은_이_점포에_입고된_적_없는_상품의_재고가_null이다() throws Exception {
        long storeId = 7403L;
        long stocked = product("P7405", "입고된 빵");
        long never = product("P7406", "입고 안 된 빵");
        inbound(storeId, "P7405", 7, FAR);

        owner(get("/owner/inventory/first-come"), storeId).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.productId == " + stocked + ")].stock.availableQuantity").value(contains(7)))
                .andExpect(jsonPath("$[?(@.productId == " + never + ")].stock").value(contains(nullValue())));
    }

    @Test
    void 재고_목록과_추천_설정에_상품_브랜드가_붙고_없으면_null이다() throws Exception {
        long storeId = 7404L;
        long branded = product("P7407", "브랜드 우유");
        long plain = product("P7408", "브랜드 없는 김밥");
        jdbcTemplate.update("UPDATE product SET brand = ? WHERE id = ?", "빙그레", branded);
        inbound(storeId, "P7407", 5, FAR);
        inbound(storeId, "P7408", 5, FAR);

        owner(get("/owner/inventory").param("size", "100"), storeId).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.productId == " + branded + ")].brand").value(contains("빙그레")))
                .andExpect(jsonPath("$.items[?(@.productId == " + plain + ")].brand").value(contains(nullValue())));
        owner(get("/owner/inventory/recommendations"), storeId).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.productId == " + branded + ")].stock.brand").value(contains("빙그레")));
    }

    private ResultActions owner(MockHttpServletRequestBuilder request, long storeId) throws Exception {
        return mockMvc.perform(request.header("X-Store-Id", String.valueOf(storeId)));
    }

    private void safetyStock(long storeId, long productId, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO safety_stock (store_id, product_id, quantity, created_at, updated_at)
                VALUES (?, ?, ?, NOW(6), NOW(6))
                """, storeId, productId, quantity);
    }

    private void inbound(long storeId, String productCode, int quantity, String expiry) {
        handle(new InboundMessage(UUID.randomUUID().toString(), NOW, storeId, "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(expiry)))));
    }

    private void handle(BaseMessage message) {
        inventoryCommandService.handle(message);
    }

    private long product(String productCode, String name) {
        jdbcTemplate.update("INSERT INTO product (product_code, name, category, price) VALUES (?, ?, ?, ?)",
                productCode, name, "목록칸시험", 1000L);
        return jdbcTemplate.queryForObject("SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }
}
