package com.freshlink.inventory;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReserveMessage;
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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품별 판매 순위. 원장 SALE(결제 확정으로 실물이 줄어든 것)을 상품별로 더해 많은 순으로 매긴다.
 * 지금 시각은 2026-10-25 12:00 KST. 판매는 쪽지 처리나 SQL로 넣는다. 점포 4301~4399, 상품 P43xx, 주문 43xx를 쓴다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class SalesRankingTest {

    static final String NORMAL = "2027-12-31T23:59:59+09:00";
    static final LocalDateTime IN_RANGE = LocalDateTime.of(2026, 10, 24, 15, 0);

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
    void 판매_수량이_많은_순으로_매기고_같은_수량은_같은_순위다() throws Exception {
        long storeId = 4301L;
        long a = product("P4301", "순위 가 도시락");
        long b = product("P4302", "순위 나 우유");
        long c = product("P4303", "순위 다 라면");
        long d = product("P4304", "순위 라 빵");
        long e = product("P4305", "순위 마 과자");
        sale(storeId, "P4301", 2);
        sale(storeId, "P4301", 3);
        sale(storeId, "P4302", 3);
        sale(storeId, "P4303", 3);
        sale(storeId, "P4304", 1);
        // 폐기만 있는 상품, 다른 점포 판매, 기간 밖 판매는 빠진다
        ledger(storeId, lot(storeId, "P4305", 5), "DISPOSE", -5, IN_RANGE);
        ledger(4302L, lot(4302L, "P4301", 9), "SALE", -9, IN_RANGE);
        ledger(storeId, lot(storeId, "P4304", 1), "SALE", -7, LocalDateTime.of(2026, 10, 10, 9, 0));

        ranking("4301", "?from=2026-10-19&to=2026-10-25")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.from").value("2026-10-19"))
                .andExpect(jsonPath("$.to").value("2026-10-25"))
                .andExpect(jsonPath("$.totalSoldQuantity").value(12))
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(a))
                .andExpect(jsonPath("$.items[0].soldQuantity").value(5))
                .andExpect(jsonPath("$.items[0].share").value(0.4167))
                // 3개 판매가 둘이라 둘 다 2위, 다음은 4위. 같은 순위 안에서는 상품명 순
                .andExpect(jsonPath("$.items[1].rank").value(2))
                .andExpect(jsonPath("$.items[1].productId").value(b))
                .andExpect(jsonPath("$.items[2].rank").value(2))
                .andExpect(jsonPath("$.items[2].productId").value(c))
                .andExpect(jsonPath("$.items[3].rank").value(4))
                .andExpect(jsonPath("$.items[3].productId").value(d))
                .andExpect(jsonPath("$.items[3].name").value("순위 라 빵"));
    }

    @Test
    void 결제가_확정된_수량만_세고_예약만_된_수량은_세지_않는다() throws Exception {
        long storeId = 4303L;
        long productId = product("P4306", "순위 확정 김밥");
        inbound(storeId, "P4306", 10);
        // 주문 4301: 3개 예약 → 결제 확정. 주문 4302: 2개 예약만 (결제 대기)
        handle(new ReserveMessage(newId(), NOW, 4301L, storeId, productId, 3));
        handle(new ConfirmMessage(newId(), NOW, 4301L, storeId));
        handle(new ReserveMessage(newId(), NOW, 4302L, storeId, productId, 2));

        ranking("4303", "")
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].soldQuantity").value(3));
    }

    @Test
    void limit_개수만큼만_준다() throws Exception {
        long storeId = 4304L;
        product("P4307", "순위 한계 1");
        product("P4308", "순위 한계 2");
        product("P4309", "순위 한계 3");
        sale(storeId, "P4307", 3);
        sale(storeId, "P4308", 2);
        sale(storeId, "P4309", 1);

        ranking("4304", "?limit=2")
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].name").value("순위 한계 2"))
                // 비중은 잘라 내기 전 전체 판매(6개) 기준이다
                .andExpect(jsonPath("$.totalSoldQuantity").value(6));
    }

    @Test
    void 기간을_빼면_오늘까지_7일이다() throws Exception {
        long storeId = 4305L;
        product("P4310", "순위 기본 기간");
        long lot = lot(storeId, "P4310", 5);
        ledger(storeId, lot, "SALE", -1, LocalDateTime.of(2026, 10, 19, 0, 0));
        ledger(storeId, lot, "SALE", -1, LocalDateTime.of(2026, 10, 18, 23, 59, 59));

        ranking("4305", "")
                .andExpect(jsonPath("$.from").value("2026-10-19"))
                .andExpect(jsonPath("$.to").value("2026-10-25"))
                .andExpect(jsonPath("$.items[0].soldQuantity").value(1));
    }

    @Test
    void 판매가_없으면_빈_목록이다() throws Exception {
        ranking("4306", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSoldQuantity").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void limit이_1부터_100이_아니거나_시작일이_늦으면_400이다() throws Exception {
        ranking("4307", "?limit=0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
        ranking("4307", "?limit=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
        ranking("4307", "?from=2026-10-25&to=2026-10-19")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    private ResultActions ranking(String storeId, String query) throws Exception {
        return mockMvc.perform(get("/owner/inventory/sales-ranking" + query).header("X-Store-Id", storeId));
    }

    /** 기간 안(10/24)에 n개 판매를 원장에 넣는다 */
    private void sale(long storeId, String productCode, int quantity) {
        ledger(storeId, lot(storeId, productCode, quantity), "SALE", -quantity, IN_RANGE);
    }

    /** 그 점포·상품의 lot. 없으면 입고해서 만든다 */
    private long lot(long storeId, String productCode, int quantity) {
        List<Long> ids = jdbcTemplate.queryForList("""
                SELECT lot.id FROM inventory_lot lot JOIN product p ON p.id = lot.product_id
                WHERE lot.store_id = ? AND p.product_code = ?
                """, Long.class, storeId, productCode);
        if (!ids.isEmpty()) {
            return ids.getFirst();
        }
        inbound(storeId, productCode, quantity);
        return lot(storeId, productCode, quantity);
    }

    private void inbound(long storeId, String productCode, int quantity) {
        handle(new InboundMessage(newId(), NOW, storeId, "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(NORMAL)))));
    }

    private long product(String productCode, String name) {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, name, "테스트", 1500L);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private void ledger(long storeId, long lotId, String type, int quantity, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO ledger (store_id, lot_id, type, quantity, ref_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, storeId, lotId, type, quantity, "ranking-test", createdAt);
    }

    private void handle(BaseMessage message) {
        inventoryCommandService.handle(message);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
