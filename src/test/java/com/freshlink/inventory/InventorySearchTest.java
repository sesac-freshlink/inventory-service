package com.freshlink.inventory;

import com.freshlink.inventory.common.web.XStoreIdFilter;
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

import java.time.LocalDateTime;
import java.util.List;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-10 점주 재고 목록의 검색·필터·페이지. 집계(합산·경과 제외·임박·부족)와 거르기·자르기를 SQL 한 번으로 하는지
 * 실제 MySQL로 본다. 지금 시각은 2026-10-25 12:00 KST. lot은 SQL로 바로 넣는다. 점포 4501~4599, 상품 P45xx를 쓴다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class InventorySearchTest {

    static final LocalDateTime NOW_KST = NOW.toLocalDateTime();
    static final LocalDateTime FRESH = LocalDateTime.of(2027, 12, 31, 23, 59, 59);
    /** 24시간 안 → 임박 */
    static final LocalDateTime SOON = NOW_KST.plusHours(2);
    /** 지금과 같은 시각 → 경과 (기한 > 지금인 lot만 판다) */
    static final LocalDateTime PASSED = NOW_KST;

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    XStoreIdFilter xStoreIdFilter;

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
    void 상품별로_lot을_합산하고_경과lot은_구매가능에서_빼며_임박_경과_lot을_센다() throws Exception {
        long storeId = 4501L;
        long productId = product("P4501", "검색 라면");
        lot(storeId, productId, FRESH, 8, 3);
        lot(storeId, productId, SOON, 2, 0);
        lot(storeId, productId, PASSED, 4, 0);
        // 수량이 0인 lot은 가장 빠른 기한·임박·경과 수에서 빠진다
        lot(storeId, productId, NOW_KST.minusDays(3), 0, 0);

        search("4501", "")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(productId))
                .andExpect(jsonPath("$.items[0].productCode").value("P4501"))
                .andExpect(jsonPath("$.items[0].totalQuantity").value(14))
                .andExpect(jsonPath("$.items[0].reservedQuantity").value(3))
                // (8 − 3) + 2. 경과 lot 4개는 빠진다
                .andExpect(jsonPath("$.items[0].availableQuantity").value(7))
                .andExpect(jsonPath("$.items[0].nearestExpiry").value("2026-10-25T12:00:00+09:00"))
                .andExpect(jsonPath("$.items[0].imminentLotCount").value(1))
                .andExpect(jsonPath("$.items[0].expiredLotCount").value(1))
                .andExpect(jsonPath("$.items[0].safetyStock").value(5))
                .andExpect(jsonPath("$.items[0].lowStock").value(false))
                .andExpect(jsonPath("$.items[0].hasMemo").value(false));
    }

    @Test
    void 안전재고와_메모는_점주가_정한_값을_쓰고_구매가능이_안전재고보다_적을_때만_부족이다() throws Exception {
        long storeId = 4502L;
        long ramen = product("P4502", "검색 부족 라면");
        long milk = product("P4503", "검색 같은 우유");
        lot(storeId, ramen, FRESH, 4, 0);   // 기본값 5보다 적다 → 부족
        lot(storeId, milk, FRESH, 10, 0);   // 안전재고 10과 같다 → 부족 아님
        jdbcTemplate.update("""
                INSERT INTO safety_stock (store_id, product_id, quantity, created_at, updated_at)
                VALUES (?, ?, 10, NOW(3), NOW(3))
                """, storeId, milk);
        jdbcTemplate.update("""
                INSERT INTO product_memo (store_id, product_id, content, version, created_at, updated_at)
                VALUES (?, ?, '냉장 2칸', 0, NOW(3), NOW(3))
                """, storeId, milk);

        String body = body("4502", "");
        assertThat(field(body, ramen, "lowStock")).containsExactly(true);
        assertThat(field(body, milk, "lowStock")).containsExactly(false);
        assertThat(field(body, milk, "safetyStock")).containsExactly(10);
        assertThat(field(body, milk, "hasMemo")).containsExactly(true);
        assertThat(JsonPath.<Integer>read(body, "$.lowStockCount")).isEqualTo(1);
    }

    @Test
    void 상품명이나_상품코드에_검색어가_들어간_상품만_준다() throws Exception {
        long storeId = 4503L;
        lot(storeId, product("P4504", "검색 흰 우유 500ml"), FRESH, 3, 0);
        lot(storeId, product("P4505", "검색 바나나 우유"), FRESH, 3, 0);
        lot(storeId, product("P4506", "검색 컵라면"), FRESH, 3, 0);

        assertThat(names(body("4503", "?q=우유"))).containsExactly("검색 바나나 우유", "검색 흰 우유 500ml");
        // 대소문자를 가리지 않는다
        assertThat(names(body("4503", "?q=p4506"))).containsExactly("검색 컵라면");
        assertThat(names(body("4503", "?q=없는상품"))).isEmpty();
    }

    @Test
    void status는_임박_경과_품절_부족을_거르고_여러_개면_모두_만족해야_한다() throws Exception {
        long storeId = 4504L;
        lot(storeId, product("P4507", "검색 임박"), SOON, 9, 0);
        lot(storeId, product("P4508", "검색 경과"), PASSED, 9, 0);
        lot(storeId, product("P4509", "검색 품절"), FRESH, 2, 2);
        lot(storeId, product("P4510", "검색 넉넉"), FRESH, 30, 0);
        long both = product("P4511", "검색 임박 부족");
        lot(storeId, both, SOON, 1, 0);

        assertThat(names(body("4504", "?status=IMMINENT"))).containsExactly("검색 임박", "검색 임박 부족");
        assertThat(names(body("4504", "?status=EXPIRED"))).containsExactly("검색 경과");
        // 경과 lot만 있으면 구매 가능 0이라 품절에도 걸린다
        assertThat(names(body("4504", "?status=SOLD_OUT"))).containsExactly("검색 경과", "검색 품절");
        assertThat(names(body("4504", "?status=LOW_STOCK")))
                .containsExactly("검색 경과", "검색 임박 부족", "검색 품절");
        assertThat(names(body("4504", "?status=IMMINENT,LOW_STOCK"))).containsExactly("검색 임박 부족");
        assertThat(names(body("4504", "?status=IMMINENT&q=부족"))).containsExactly("검색 임박 부족");
    }

    @Test
    void memo가_true면_메모가_있는_상품만_준다() throws Exception {
        long storeId = 4505L;
        long withMemo = product("P4512", "검색 메모 있음");
        lot(storeId, withMemo, FRESH, 3, 0);
        lot(storeId, product("P4513", "검색 메모 없음"), FRESH, 3, 0);
        jdbcTemplate.update("""
                INSERT INTO product_memo (store_id, product_id, content, version, created_at, updated_at)
                VALUES (?, ?, '메모', 0, NOW(3), NOW(3))
                """, storeId, withMemo);

        assertThat(names(body("4505", "?memo=true"))).containsExactly("검색 메모 있음");
        assertThat(names(body("4505", "?memo=false"))).hasSize(2);
    }

    @Test
    void 상품명_순으로_page와_size만큼_자르고_전체_개수와_페이지_수를_준다() throws Exception {
        long storeId = 4506L;
        for (int i = 1; i <= 5; i++) {
            lot(storeId, product("P45" + (20 + i), "검색 페이지 " + i), FRESH, 3, 0);
        }

        search("4506", "?page=0&size=2")
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items[0].name").value("검색 페이지 1"))
                .andExpect(jsonPath("$.items[1].name").value("검색 페이지 2"));
        assertThat(names(body("4506", "?page=2&size=2"))).containsExactly("검색 페이지 5");
        assertThat(names(body("4506", "?page=3&size=2"))).isEmpty();
        // size를 빼면 20
        search("4506", "").andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void 부족_건수는_검색_필터_페이지와_상관없이_점포_전체로_센다() throws Exception {
        long storeId = 4507L;
        lot(storeId, product("P4531", "검색 건수 부족1"), FRESH, 1, 0);
        lot(storeId, product("P4532", "검색 건수 부족2"), FRESH, 2, 0);
        lot(storeId, product("P4533", "검색 건수 넉넉"), FRESH, 30, 0);

        search("4507", "?q=넉넉&size=1")
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.lowStockCount").value(2));
    }

    @Test
    void 다른_점포_lot과_입고가_없는_점포는_보이지_않는다() throws Exception {
        lot(4508L, product("P4534", "검색 다른 점포"), FRESH, 3, 0);

        search("4509", "")
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.lowStockCount").value(0));
    }

    @Test
    void page가_음수이거나_size가_1부터_100이_아니거나_status가_틀리면_400이다() throws Exception {
        search("4510", "?page=-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        search("4510", "?size=0").andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        search("4510", "?size=101").andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        search("4510", "?status=NOPE").andExpect(status().isBadRequest());
    }

    @Test
    void 기한이_없는_상품은_없고_수량이_모두_0이면_가장_빠른_기한은_null이다() throws Exception {
        long storeId = 4511L;
        long productId = product("P4535", "검색 다 팔림");
        lot(storeId, productId, FRESH, 0, 0);

        search("4511", "")
                .andExpect(jsonPath("$.items[0].totalQuantity").value(0))
                .andExpect(jsonPath("$.items[0].nearestExpiry").value(nullValue()))
                .andExpect(jsonPath("$.items[0].lowStock").value(true));
    }

    private ResultActions search(String storeId, String query) throws Exception {
        return mockMvc.perform(get("/owner/inventory" + query).header("X-Store-Id", storeId));
    }

    private String body(String storeId, String query) throws Exception {
        return search(storeId, query)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static List<String> names(String body) {
        return JsonPath.read(body, "$.items[*].name");
    }

    private static List<Object> field(String body, long productId, String name) {
        return JsonPath.read(body, "$.items[?(@.productId == " + productId + ")]." + name);
    }

    private long product(String productCode, String name) {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, name, "테스트", 1500L);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private void lot(long storeId, long productId, LocalDateTime expiry, int quantity, int reserved) {
        jdbcTemplate.update("""
                INSERT INTO inventory_lot
                    (store_id, product_id, expiry_date, quantity, reserved_quantity, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'NORMAL', NOW(3), NOW(3))
                """, storeId, productId, expiry, quantity, reserved);
    }
}
