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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 점주 상품 메모. 상품 하나에 메모 하나, 처음 저장할 때 생기고 자유롭게 고친다.
 * 화면이 읽은 version으로 저장하고, 그 사이 다른 화면이 바꿨으면 409 MEMO_CHANGED (낙관적 락).
 * 점포 4901~4999, 상품 P49xx를 쓴다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class ProductMemoTest {

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
    void 처음_저장하면_만들어지고_메모_목록과_재고_목록에_보인다() throws Exception {
        long productId = product("P4901");
        inbound(4901L, "P4901", 5);

        putMemo("4901", productId, "냉장 2칸에 둠", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(productId))
                .andExpect(jsonPath("$.productCode").value("P4901"))
                .andExpect(jsonPath("$.content").value("냉장 2칸에 둠"))
                .andExpect(jsonPath("$.version").value(0));

        mockMvc.perform(get("/owner/inventory/memos").header("X-Store-Id", "4901"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("메모 P4901"))
                .andExpect(jsonPath("$[0].content").value("냉장 2칸에 둠"));

        assertThat(hasMemo("4901", productId)).containsExactly(true);
    }

    @Test
    void 읽은_version으로_고치면_내용이_바뀌고_version이_오른다() throws Exception {
        long productId = product("P4902");
        putMemo("4902", productId, "첫 메모", null).andExpect(status().isOk());

        putMemo("4902", productId, "다음 주 입고 늘리기", 0L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("다음 주 입고 늘리기"))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(memoCount(4902L, productId)).isEqualTo(1);
    }

    @Test
    void 다른_화면이_먼저_고쳤으면_409_MEMO_CHANGED이고_내용은_그대로다() throws Exception {
        long productId = product("P4903");
        putMemo("4903", productId, "포스기에서 씀", null).andExpect(status().isOk());
        // 휴대폰에서 version 0을 읽고 먼저 고쳤다 → version 1
        putMemo("4903", productId, "휴대폰에서 고침", 0L).andExpect(status().isOk());

        // 포스기는 아직 version 0을 들고 있다
        putMemo("4903", productId, "포스기에서 고침", 0L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMO_CHANGED"));

        assertThat(content(4903L, productId)).isEqualTo("휴대폰에서 고침");
    }

    @Test
    void version_없이_만들려는데_그_사이_다른_화면이_먼저_만들었으면_409다() throws Exception {
        long productId = product("P4904");
        putMemo("4904", productId, "먼저 만든 메모", null).andExpect(status().isOk());

        putMemo("4904", productId, "늦게 만든 메모", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMO_CHANGED"));

        assertThat(content(4904L, productId)).isEqualTo("먼저 만든 메모");
    }

    @Test
    void 다른_화면이_지운_메모를_고치려_하면_409다() throws Exception {
        long productId = product("P4905");
        putMemo("4905", productId, "곧 지워질 메모", null).andExpect(status().isOk());
        deleteMemo("4905", productId, 0L).andExpect(status().isNoContent());

        putMemo("4905", productId, "지워진 줄 모르고 고침", 0L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMO_CHANGED"));

        assertThat(memoCount(4905L, productId)).isZero();
    }

    @Test
    void 지우면_204이고_메모_목록과_재고_목록에서_빠진다() throws Exception {
        long productId = product("P4906");
        inbound(4906L, "P4906", 5);
        putMemo("4906", productId, "지울 메모", null).andExpect(status().isOk());

        deleteMemo("4906", productId, 0L).andExpect(status().isNoContent());

        mockMvc.perform(get("/owner/inventory/memos").header("X-Store-Id", "4906"))
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(hasMemo("4906", productId)).containsExactly(false);
        // 이미 없는 메모를 다시 지워도 결과가 같다
        deleteMemo("4906", productId, null).andExpect(status().isNoContent());
    }

    @Test
    void 다른_화면이_고친_메모를_낡은_version으로_지우려_하면_409이고_남는다() throws Exception {
        long productId = product("P4907");
        putMemo("4907", productId, "처음", null).andExpect(status().isOk());
        putMemo("4907", productId, "다른 화면이 고침", 0L).andExpect(status().isOk());

        deleteMemo("4907", productId, 0L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMO_CHANGED"));

        assertThat(content(4907L, productId)).isEqualTo("다른 화면이 고침");
    }

    @Test
    void 메모는_점포마다_따로다() throws Exception {
        long productId = product("P4908");
        putMemo("4908", productId, "4908 점포 메모", null).andExpect(status().isOk());

        mockMvc.perform(get("/owner/inventory/memos").header("X-Store-Id", "4909"))
                .andExpect(jsonPath("$.length()").value(0));
        // 다른 점포는 같은 상품에 자기 메모를 따로 만든다
        putMemo("4909", productId, "4909 점포 메모", null).andExpect(status().isOk());
        assertThat(content(4908L, productId)).isEqualTo("4908 점포 메모");
    }

    @Test
    void 빈_내용_200자_넘는_내용_없는_상품은_저장하지_않는다() throws Exception {
        long productId = product("P4910");

        putMemo("4910", productId, "   ", null).andExpect(status().isBadRequest());
        putMemo("4910", productId, "가".repeat(201), null).andExpect(status().isBadRequest());
        putMemo("4910", 999_999L, "없는 상품", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        putMemo("4910", productId, "가".repeat(200), null).andExpect(status().isOk());

        assertThat(memoCount(4910L, productId)).isEqualTo(1);
    }

    private ResultActions putMemo(String storeId, long productId, String content, Long version) throws Exception {
        String body = "{\"content\":\"" + content + "\"" + (version == null ? "" : ",\"version\":" + version) + "}";
        return mockMvc.perform(put("/owner/inventory/memos/" + productId)
                .header("X-Store-Id", storeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions deleteMemo(String storeId, long productId, Long version) throws Exception {
        var request = delete("/owner/inventory/memos/" + productId).header("X-Store-Id", storeId);
        if (version != null) {
            request.param("version", String.valueOf(version));
        }
        return mockMvc.perform(request);
    }

    private List<Boolean> hasMemo(String storeId, long productId) throws Exception {
        String body = mockMvc.perform(get("/owner/inventory").header("X-Store-Id", storeId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[?(@.productId == " + productId + ")].hasMemo");
    }

    private int memoCount(long storeId, long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_memo WHERE store_id = ? AND product_id = ?",
                Integer.class, storeId, productId);
    }

    private String content(long storeId, long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM product_memo WHERE store_id = ? AND product_id = ?",
                String.class, storeId, productId);
    }

    private void inbound(long storeId, String productCode, int quantity) {
        inventoryCommandService.handle(new InboundMessage(
                UUID.randomUUID().toString(), NOW, storeId, "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse("2027-12-31T23:59:59+09:00")))));
    }

    private long product(String productCode) {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, "메모 " + productCode, "테스트", 1500L);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }
}
