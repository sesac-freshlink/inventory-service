package com.freshlink.inventory;

import com.freshlink.inventory.common.web.XStoreIdFilter;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 유통기한 달력. 그 달에 기한이 끝나는 lot(수량이 남은 것)을 날짜별로 묶어 lot 목록과 함께 준다.
 * 상태(정상·임박·경과)는 저장된 status가 아니라 지금 시각으로 계산한다. 지금 시각은 2026-10-25 12:00 KST.
 * lot은 SQL로 바로 넣는다(status 열은 일부러 모두 NORMAL). 점포 5201~5299, 상품 P52xx를 쓴다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class ExpiryCalendarTest {

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
    void 그_달에_끝나는_lot을_날짜별로_묶고_날짜마다_lot_수_수량_lot_목록을_준다() throws Exception {
        long storeId = 5201L;
        long milk = product("P5201", "달력 가 우유");
        long ramen = product("P5202", "달력 나 라면");
        long bread = product("P5203", "달력 다 빵");
        long milkLot = lot(storeId, milk, LocalDateTime.of(2026, 10, 25, 23, 59, 59), 3, 0);
        lot(storeId, ramen, LocalDateTime.of(2026, 10, 25, 23, 59, 59), 4, 0);
        long breadLot = lot(storeId, bread, LocalDateTime.of(2026, 10, 20, 23, 59, 59), 2, 0);
        lot(storeId, ramen, LocalDateTime.of(2026, 10, 31, 23, 59, 59), 5, 1);
        // 다음 달, 수량 0, 다른 점포는 빠진다
        lot(storeId, milk, LocalDateTime.of(2026, 11, 1, 0, 0), 1, 0);
        lot(storeId, bread, LocalDateTime.of(2026, 10, 28, 23, 59, 59), 0, 0);
        lot(5202L, milk, LocalDateTime.of(2026, 10, 25, 23, 59, 59), 9, 0);

        calendar("5201", "?month=2026-10")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.month").value("2026-10"))
                .andExpect(jsonPath("$.days.length()").value(3))
                .andExpect(jsonPath("$.days[0].date").value("2026-10-20"))
                .andExpect(jsonPath("$.days[0].lotCount").value(1))
                .andExpect(jsonPath("$.days[0].quantity").value(2))
                .andExpect(jsonPath("$.days[0].lots[0].lotId").value(breadLot))
                .andExpect(jsonPath("$.days[0].lots[0].productCode").value("P5203"))
                .andExpect(jsonPath("$.days[0].lots[0].name").value("달력 다 빵"))
                .andExpect(jsonPath("$.days[0].lots[0].expiryDate").value("2026-10-20T23:59:59+09:00"))
                // 저장된 status는 NORMAL이지만 지금 시각으로 계산하면 경과
                .andExpect(jsonPath("$.days[0].lots[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$.days[1].date").value("2026-10-25"))
                .andExpect(jsonPath("$.days[1].lotCount").value(2))
                .andExpect(jsonPath("$.days[1].quantity").value(7))
                // 같은 날은 상품명 순. 24시간 안이라 임박
                .andExpect(jsonPath("$.days[1].lots[0].lotId").value(milkLot))
                .andExpect(jsonPath("$.days[1].lots[0].status").value("IMMINENT"))
                .andExpect(jsonPath("$.days[1].lots[1].name").value("달력 나 라면"))
                .andExpect(jsonPath("$.days[2].date").value("2026-10-31"))
                .andExpect(jsonPath("$.days[2].lots[0].quantity").value(5))
                .andExpect(jsonPath("$.days[2].lots[0].reservedQuantity").value(1))
                .andExpect(jsonPath("$.days[2].lots[0].status").value("NORMAL"));
    }

    @Test
    void 다음_달_0시에_끝나는_lot은_다음_달_첫날에_들어간다() throws Exception {
        long storeId = 5203L;
        long productId = product("P5204", "달력 경계 김밥");
        lot(storeId, productId, LocalDateTime.of(2026, 11, 1, 0, 0), 1, 0);

        calendar("5203", "?month=2026-10").andExpect(jsonPath("$.days.length()").value(0));
        calendar("5203", "?month=2026-11")
                .andExpect(jsonPath("$.days[0].date").value("2026-11-01"))
                .andExpect(jsonPath("$.days[0].lots[0].status").value("NORMAL"));
    }

    @Test
    void month를_빼면_이번_달이다() throws Exception {
        long storeId = 5204L;
        lot(storeId, product("P5205", "달력 기본 달"), LocalDateTime.of(2026, 10, 30, 23, 59, 59), 2, 0);

        calendar("5204", "")
                .andExpect(jsonPath("$.month").value("2026-10"))
                .andExpect(jsonPath("$.days[0].date").value("2026-10-30"));
    }

    @Test
    void lot이_없는_달은_빈_목록이고_month_모양이_틀리면_400이다() throws Exception {
        calendar("5205", "?month=2026-09")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(0));
        calendar("5205", "?month=2026-13").andExpect(status().isBadRequest());
        calendar("5205", "?month=10월").andExpect(status().isBadRequest());
    }

    private ResultActions calendar(String storeId, String query) throws Exception {
        return mockMvc.perform(get("/owner/inventory/expiry-calendar" + query).header("X-Store-Id", storeId));
    }

    private long product(String productCode, String name) {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, name, "테스트", 1500L);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private long lot(long storeId, long productId, LocalDateTime expiry, int quantity, int reserved) {
        jdbcTemplate.update("""
                INSERT INTO inventory_lot
                    (store_id, product_id, expiry_date, quantity, reserved_quantity, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'NORMAL', NOW(3), NOW(3))
                """, storeId, productId, expiry, quantity, reserved);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_lot WHERE store_id = ? AND product_id = ? AND expiry_date = ?",
                Long.class, storeId, productId, expiry);
    }
}
