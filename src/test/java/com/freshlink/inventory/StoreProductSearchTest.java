package com.freshlink.inventory;

import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import com.jayway.jsonpath.JsonPath;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U-10 고객 상품 목록의 상품명 검색. 상품과 고객용 재고 조회 표를 한 쿼리로 묶고 상품명으로 거른다.
 * 상품은 모든 점포가 같이 쓰므로(다른 테스트 상품도 섞인다) 이 테스트 상품 이름만 골라 본다.
 * 지금 시각은 2026-10-25 12:00 KST. 점포 5001~5099, 상품 P50xx를 쓴다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class StoreProductSearchTest {

    static final String FRESH = "2027-12-31T23:59:59+09:00";

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
    void 상품명에_검색어가_들어간_상품만_상품명_순으로_구매가능수량과_함께_준다() throws Exception {
        long storeId = 5001L;
        product("P5001", "고객검색 흰 우유 500ml");
        product("P5002", "고객검색 바나나 우유");
        product("P5003", "고객검색 컵라면");
        inbound(storeId, "P5001", 4);

        String body = products("5001", "고객검색 우유")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<List<String>>read(body, "$[*].name")).isEmpty();

        body = products("5001", "우유").andReturn().getResponse().getContentAsString();
        assertThat(names(body, "고객검색")).containsExactly("고객검색 바나나 우유", "고객검색 흰 우유 500ml");
        // 입고된 흰 우유는 4개, 입고 안 된 바나나 우유는 품절
        assertThat(JsonPath.<List<Integer>>read(body, "$[?(@.name == '고객검색 흰 우유 500ml')].availableQuantity"))
                .containsExactly(4);
        assertThat(JsonPath.<List<Boolean>>read(body, "$[?(@.name == '고객검색 바나나 우유')].soldOut"))
                .containsExactly(true);
    }

    @Test
    void 검색어를_빼거나_공백이면_상품_전체를_준다() throws Exception {
        product("P5004", "고객검색 전체 김밥");

        String all = products("5002", null).andReturn().getResponse().getContentAsString();
        String blank = products("5002", "  ").andReturn().getResponse().getContentAsString();

        assertThat(names(all, "고객검색 전체")).containsExactly("고객검색 전체 김밥");
        assertThat(JsonPath.<List<String>>read(blank, "$[*].name"))
                .isEqualTo(JsonPath.<List<String>>read(all, "$[*].name"));
    }

    @Test
    void 검색어의_퍼센트와_밑줄은_글자_그대로_찾는다() throws Exception {
        product("P5005", "고객검색 100% 오렌지");
        product("P5006", "고객검색 1000 오렌지");

        String body = products("5003", "100%").andReturn().getResponse().getContentAsString();

        assertThat(names(body, "고객검색")).containsExactly("고객검색 100% 오렌지");
    }

    @Test
    void 값이_맞는_시각이_지난_조회표_행은_검색해도_품절로_보인다() throws Exception {
        long storeId = 5004L;
        long productId = product("P5007", "고객검색 시각 지난 빵");
        inbound(storeId, "P5007", 6);
        jdbcTemplate.update("UPDATE store_product_stock SET valid_until = ? WHERE store_id = ? AND product_id = ?",
                NOW.toLocalDateTime(), storeId, productId);

        products("5004", "시각 지난 빵")
                .andExpect(jsonPath("$[0].availableQuantity").value(0))
                .andExpect(jsonPath("$[0].soldOut").value(true));
    }

    @Test
    void 검색어가_50자를_넘으면_400이다() throws Exception {
        products("5005", "가".repeat(51))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        products("5005", "가".repeat(50)).andExpect(status().isOk());
    }

    /** q가 null이면 검색어 없이 */
    private ResultActions products(String storeId, String q) throws Exception {
        var request = get("/stores/" + storeId + "/products");
        if (q != null) {
            request.param("q", q);
        }
        return mockMvc.perform(request);
    }

    private static List<String> names(String body, String prefix) {
        return JsonPath.<List<String>>read(body, "$[*].name").stream()
                .filter(name -> name.startsWith(prefix))
                .toList();
    }

    private long product(String productCode, String name) {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, name, "테스트", 1500L);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private void inbound(long storeId, String productCode, int quantity) {
        inventoryCommandService.handle(new InboundMessage(UUID.randomUUID().toString(), NOW, storeId,
                "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(FRESH)))));
    }
}
