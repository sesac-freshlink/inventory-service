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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-42 판매·폐기 통계. 원장 GROUP BY 집계를 실제 MySQL로 확인한다. 지금 시각은 2026-10-25 12:00 KST.
 * 입고·판매는 쪽지 처리로, 폐기와 기간 밖 원장은 SQL로 넣는다. 점포 4201~4299, 상품 P42xx, 주문 42xx를 쓴다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class InventoryStatsTest {

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
    void 기간_안의_입고_판매_폐기를_상품별로_더하고_폐기_많은_순으로_준다() throws Exception {
        long storeId = 4201L;
        long ramen = product("P4201", "통계 라면");
        long milk = product("P4202", "통계 우유");
        long ramenLot = inbound(storeId, "P4201", 10);
        long milkLot = inbound(storeId, "P4202", 6);
        handle(new ReserveMessage(UUID.randomUUID().toString(), NOW, 4201L, storeId, ramen, 3));
        handle(new ConfirmMessage(UUID.randomUUID().toString(), NOW, 4201L, storeId));
        ledger(storeId, milkLot, "DISPOSE", -2, NOW.toLocalDateTime());
        // 기간 밖(10/10) 판매와 다른 점포 원장은 빠져야 한다
        ledger(storeId, ramenLot, "SALE", -5, LocalDateTime.of(2026, 10, 10, 9, 0));
        long otherLot = inbound(4202L, "P4201", 7);
        ledger(4202L, otherLot, "DISPOSE", -7, NOW.toLocalDateTime());

        stats("4201", "?from=2026-10-19&to=2026-10-25")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-19"))
                .andExpect(jsonPath("$.to").value("2026-10-25"))
                .andExpect(jsonPath("$.inboundQuantity").value(16))
                .andExpect(jsonPath("$.soldQuantity").value(3))
                .andExpect(jsonPath("$.disposedQuantity").value(2))
                .andExpect(jsonPath("$.disposalRate").value(0.4))
                .andExpect(jsonPath("$.products.length()").value(2))
                .andExpect(jsonPath("$.products[0].productId").value(milk))
                .andExpect(jsonPath("$.products[0].inboundQuantity").value(6))
                .andExpect(jsonPath("$.products[0].disposedQuantity").value(2))
                .andExpect(jsonPath("$.products[0].disposalRate").value(1.0))
                .andExpect(jsonPath("$.products[1].productId").value(ramen))
                .andExpect(jsonPath("$.products[1].soldQuantity").value(3))
                .andExpect(jsonPath("$.products[1].disposalRate").value(0.0));

        // 기간을 넓히면 10/10 판매 5개가 더해진다
        stats("4201", "?from=2026-10-10&to=2026-10-25")
                .andExpect(jsonPath("$.soldQuantity").value(8));
    }

    @Test
    void 기간을_빼면_오늘까지_7일이다() throws Exception {
        long storeId = 4203L;
        product("P4203", "통계 김밥");
        long lot = inbound(storeId, "P4203", 4);
        ledger(storeId, lot, "SALE", -1, LocalDateTime.of(2026, 10, 19, 0, 0));
        ledger(storeId, lot, "SALE", -1, LocalDateTime.of(2026, 10, 18, 23, 59, 59));

        stats("4203", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-19"))
                .andExpect(jsonPath("$.to").value("2026-10-25"))
                .andExpect(jsonPath("$.soldQuantity").value(1));
    }

    @Test
    void 변동이_없는_점포는_빈_목록과_폐기율_null이다() throws Exception {
        stats("4204", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(0))
                .andExpect(jsonPath("$.disposalRate").value(nullValue()));
    }

    @Test
    void 시작일이_종료일보다_늦으면_400이다() throws Exception {
        stats("4205", "?from=2026-10-25&to=2026-10-19")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    private ResultActions stats(String storeId, String query) throws Exception {
        return mockMvc.perform(get("/owner/inventory/stats" + query).header("X-Store-Id", storeId));
    }

    private long product(String productCode, String name) {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, name, "테스트", 1500L);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private long inbound(long storeId, String productCode, int quantity) {
        handle(new InboundMessage(
                UUID.randomUUID().toString(),
                NOW,
                storeId,
                "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(NORMAL)))
        ));
        return jdbcTemplate.queryForObject("""
                SELECT lot.id FROM inventory_lot lot JOIN product p ON p.id = lot.product_id
                WHERE lot.store_id = ? AND p.product_code = ?
                """, Long.class, storeId, productCode);
    }

    private void ledger(long storeId, long lotId, String type, int quantity, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO ledger (store_id, lot_id, type, quantity, ref_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, storeId, lotId, type, quantity, "stats-test", createdAt);
    }

    private void handle(BaseMessage message) {
        inventoryCommandService.handle(message);
    }
}
