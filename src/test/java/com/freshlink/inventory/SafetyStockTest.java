package com.freshlink.inventory;

import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.inventory.common.web.XStoreIdFilter;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import com.jayway.jsonpath.JsonPath;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-40 안전재고. V3 표·upsert 문장·S-10 lowStock을 실제 MySQL로 확인한다.
 * 다른 통합 테스트와 같은 설정이라 Spring 컨텍스트와 MySQL 컨테이너를 같이 쓴다. 점포 4001~4099, 상품 P40xx를 쓴다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class SafetyStockTest {

    static final String NORMAL = "2027-12-31T23:59:59+09:00";

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
    void 같은_상품을_두_번_정하면_한_행이_바뀌고_목록에_반영된다() throws Exception {
        long productId = product("P4001");

        putSafetyStock("4001", productId, 8).andExpect(status().isOk());
        putSafetyStock("4001", productId, 12).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM safety_stock WHERE store_id = 4001 AND product_id = ?",
                Integer.class, productId)).isEqualTo(1);

        String body = safetyStocks("4001");
        assertThat(JsonPath.<List<Integer>>read(body, "$[?(@.productId == " + productId + ")].safetyStock"))
                .containsExactly(12);
        assertThat(JsonPath.<List<Boolean>>read(body, "$[?(@.productId == " + productId + ")].customized"))
                .containsExactly(true);
    }

    @Test
    void 정하지_않은_상품과_다른_점포는_기본값5다() throws Exception {
        long productId = product("P4002");
        putSafetyStock("4002", productId, 20).andExpect(status().isOk());

        String otherStore = safetyStocks("4003");
        assertThat(JsonPath.<List<Integer>>read(otherStore, "$[?(@.productId == " + productId + ")].safetyStock"))
                .containsExactly(5);
        assertThat(JsonPath.<List<Boolean>>read(otherStore, "$[?(@.productId == " + productId + ")].customized"))
                .containsExactly(false);
    }

    @Test
    void 재고_목록은_구매_가능_수량이_안전재고보다_적을_때만_부족이다() throws Exception {
        long storeId = 4004L;
        long ramen = product("P4004");
        long milk = product("P4005");
        long bread = product("P4006");
        inbound(storeId, "P4004", 4);   // 기본값 5보다 적다 → 부족
        inbound(storeId, "P4005", 10);  // 안전재고 10과 같다 → 부족 아님
        inbound(storeId, "P4006", 2);   // 0으로 끄면 부족 아님
        putSafetyStock("4004", milk, 10).andExpect(status().isOk());
        putSafetyStock("4004", bread, 0).andExpect(status().isOk());

        String body = mockMvc.perform(get("/owner/inventory").header("X-Store-Id", "4004"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(lowStock(body, ramen)).containsExactly(true);
        assertThat(lowStock(body, milk)).containsExactly(false);
        assertThat(lowStock(body, bread)).containsExactly(false);
        assertThat(JsonPath.<List<Integer>>read(body, "$.items[?(@.productId == " + milk + ")].safetyStock"))
                .containsExactly(10);
    }

    @Test
    void 없는_상품이면_404이고_저장하지_않는다() throws Exception {
        putSafetyStock("4007", 999_999L, 3).andExpect(status().isNotFound());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM safety_stock WHERE store_id = 4007", Integer.class)).isZero();
    }

    private ResultActions putSafetyStock(
            String storeId, long productId, int safetyStock) throws Exception {
        return mockMvc.perform(put("/owner/inventory/safety-stocks/" + productId)
                .header("X-Store-Id", storeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"safetyStock\":" + safetyStock + "}"));
    }

    private String safetyStocks(String storeId) throws Exception {
        return mockMvc.perform(get("/owner/inventory/safety-stocks").header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private List<Boolean> lowStock(String body, long productId) {
        return JsonPath.read(body, "$.items[?(@.productId == " + productId + ")].lowStock");
    }

    private long product(String productCode) {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, "안전재고 " + productCode, "테스트", 1500L);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private void inbound(long storeId, String productCode, int quantity) {
        inventoryCommandService.handle(new InboundMessage(
                UUID.randomUUID().toString(),
                NOW,
                storeId,
                "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(NORMAL)))
        ));
    }
}
