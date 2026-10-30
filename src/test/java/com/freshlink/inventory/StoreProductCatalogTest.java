package com.freshlink.inventory;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.command.ConfirmMessage;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U-10 고객 목록의 카테고리 필터·추천순·신상품·추천상품과 점주 추천 설정 API.
 * 상품 표는 테스트끼리 같이 쓰므로 카테고리 이름을 테스트마다 따로 두고 그 카테고리로 거른다. 점포 ID는 54xx.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class StoreProductCatalogTest {

    static final String EXPIRY = "2027-12-31T23:59:59+09:00";

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
    void 카테고리를_고르면_그_카테고리_상품만_나오고_카테고리_목록에_들어간다() throws Exception {
        product("P5401", "카탈 김밥", "카탈A");
        product("P5402", "카탈 우유", "카탈B");

        list("5401", "?category=카탈A")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("카탈 김밥"));
        mockMvc.perform(get("/stores/5401/products/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem("카탈A")))
                .andExpect(jsonPath("$", hasItem("카탈B")));
    }

    @Test
    void 추천순은_최근_7일_순판매_많은_순이고_환불은_뺀다() throws Exception {
        long storeId = 5402L;
        long a = product("P5403", "인기 가 빵", "카탈인기");
        long b = product("P5404", "인기 나 빵", "카탈인기");
        long c = product("P5405", "인기 다 빵", "카탈인기");
        for (String code : List.of("P5403", "P5404", "P5405")) {
            inbound(storeId, code, 10);
        }
        sell(5401L, storeId, b, 3);
        sell(5402L, storeId, c, 1);

        list("5402", "?category=카탈인기&sort=POPULAR")
                .andExpect(jsonPath("$[*].productId").value(org.hamcrest.Matchers.contains(
                        (int) b, (int) c, (int) a)));

        // b를 전부 환불하면 순판매 0이라 판매 없는 a와 함께 상품명 순 뒤로 간다
        handle(ReturnMessage.of(5401L, storeId));
        list("5402", "?category=카탈인기&sort=POPULAR")
                .andExpect(jsonPath("$[*].productId").value(org.hamcrest.Matchers.contains(
                        (int) c, (int) a, (int) b)));
        // 기본은 상품명 순
        list("5402", "?category=카탈인기")
                .andExpect(jsonPath("$[*].productId").value(org.hamcrest.Matchers.contains(
                        (int) a, (int) b, (int) c)));
    }

    @Test
    void 신상품은_그_점포에_처음_입고된_지_7일_안인_상품이다() throws Exception {
        long storeId = 5403L;
        long fresh = product("P5406", "신상 가 젤리", "카탈신상");
        long old = product("P5407", "신상 나 젤리", "카탈신상");
        product("P5408", "신상 다 젤리", "카탈신상");   // 입고가 없으면 신상품이 아니다
        inbound(storeId, "P5406", 5);
        inbound(storeId, "P5407", 5);
        // 나 젤리는 10일 전에 처음 입고됐다. 오늘 다시 입고돼도 첫 입고일은 그대로다
        jdbcTemplate.update("""
                UPDATE ledger SET created_at = ? WHERE store_id = ? AND type = 'INBOUND'
                  AND lot_id IN (SELECT id FROM inventory_lot WHERE product_id = ?)
                """, NOW.toLocalDateTime().minusDays(10), storeId, old);
        inbound(storeId, "P5407", 3);

        list("5403", "?category=카탈신상&newOnly=true")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(fresh))
                .andExpect(jsonPath("$[0].newArrival").value(true));
        mockMvc.perform(get("/stores/5403/products/" + fresh))
                .andExpect(jsonPath("$.newArrival").value(true))
                .andExpect(jsonPath("$.recommended").value(false));
    }

    @Test
    void 점주가_추천으로_정한_상품만_추천상품으로_나오고_다른_점포에는_영향이_없다() throws Exception {
        long p1 = product("P5409", "추천 가 커피", "카탈추천");
        long p2 = product("P5410", "추천 나 커피", "카탈추천");

        owner(put("/owner/inventory/recommendations/" + p1), "5404")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommended").value(true));
        // 같은 요청을 다시 보내도 결과가 같다
        owner(put("/owner/inventory/recommendations/" + p1), "5404").andExpect(status().isOk());

        list("5404", "?category=카탈추천&recommended=true")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(p1))
                .andExpect(jsonPath("$[0].recommended").value(true));
        list("5405", "?category=카탈추천&recommended=true")
                .andExpect(jsonPath("$.length()").value(0));
        owner(get("/owner/inventory/recommendations"), "5404")
                .andExpect(jsonPath("$[?(@.productId == " + p1 + ")].recommended").value(true))
                .andExpect(jsonPath("$[?(@.productId == " + p2 + ")].recommended").value(false));

        owner(delete("/owner/inventory/recommendations/" + p1), "5404")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommended").value(false));
        list("5404", "?category=카탈추천&recommended=true")
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 없는_상품을_추천하면_404다() throws Exception {
        owner(put("/owner/inventory/recommendations/999999"), "5406")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    private ResultActions list(String storeId, String query) throws Exception {
        return mockMvc.perform(get("/stores/" + storeId + "/products" + query));
    }

    private ResultActions owner(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String storeId)
            throws Exception {
        return mockMvc.perform(request.header("X-Store-Id", storeId));
    }

    private void sell(long orderId, long storeId, long productId, int quantity) {
        handle(new ReserveMessage(newId(), NOW, orderId, storeId, productId, quantity));
        handle(new ConfirmMessage(newId(), NOW, orderId, storeId));
    }

    private void inbound(long storeId, String productCode, int quantity) {
        handle(new InboundMessage(newId(), NOW, storeId, "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(EXPIRY)))));
    }

    private void handle(BaseMessage message) {
        inventoryCommandService.handle(message);
    }

    private long product(String productCode, String name, String category) {
        jdbcTemplate.update("INSERT INTO product (product_code, name, category, price) VALUES (?, ?, ?, ?)",
                productCode, name, category, 1000L);
        return jdbcTemplate.queryForObject("SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
