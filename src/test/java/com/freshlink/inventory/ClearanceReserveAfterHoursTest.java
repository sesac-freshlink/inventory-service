package com.freshlink.inventory;

import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.common.message.result.ReserveFailReason;
import com.freshlink.common.message.result.ReserveFailedMessage;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판매 시간(06~23시) 밖의 마감세일 RESERVE. 지금 시각을 23:30 KST로 고정해서 컨텍스트가 따로 뜬다.
 * 점포 6201~, 상품 P62xx, 주문 62xx를 쓴다.
 */
@Import({TestcontainersConfiguration.class, ClearanceReserveAfterHoursTest.LateNightClock.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class ClearanceReserveAfterHoursTest {

    static final OffsetDateTime LATE = OffsetDateTime.parse("2026-10-25T23:30:00+09:00");
    static final String TONIGHT = "2026-10-25T23:59:59+09:00";
    static final String FRESH = "2026-11-05T23:59:59+09:00";

    @TestConfiguration(proxyBeanMethods = false)
    static class LateNightClock {
        @Bean
        @Primary
        Clock lateNightClock() {
            return Clock.fixed(LATE.toInstant(), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired
    InventoryCommandService inventoryCommandService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 판매_시간_밖의_마감세일_주문은_재고가_있어도_CLEARANCE_SOLD_OUT에_남은_수량_0이다() {
        long storeId = 6201L;
        long productId = product("P6201");
        inbound(storeId, "P6201", 5, TONIGHT);
        inbound(storeId, "P6201", 2, FRESH);

        inventoryCommandService.handle(new ReserveMessage(
                UUID.randomUUID().toString(), LATE, 6201L, storeId, productId, 1, 1));

        ReserveFailedMessage failed = (ReserveFailedMessage) MessageJson.fromJson(jdbcTemplate.queryForObject("""
                SELECT payload FROM outbox WHERE JSON_UNQUOTE(JSON_EXTRACT(payload, '$.orderId')) = '6201'
                """, String.class));
        assertThat(failed.reason()).isEqualTo(ReserveFailReason.CLEARANCE_SOLD_OUT);
        assertThat(failed.clearanceAvailableQuantity()).isZero();
        assertThat(failed.availableQuantity()).isEqualTo(2);
    }

    @Test
    void 판매_시간_밖에도_기본_주문은_24시간_밖_lot에서_잡는다() {
        long storeId = 6202L;
        long productId = product("P6202");
        inbound(storeId, "P6202", 5, TONIGHT);
        inbound(storeId, "P6202", 2, FRESH);

        inventoryCommandService.handle(new ReserveMessage(
                UUID.randomUUID().toString(), LATE, 6202L, storeId, productId, 2, 0));

        assertThat(jdbcTemplate.queryForList(
                "SELECT reserved_quantity FROM inventory_lot WHERE store_id = ? ORDER BY expiry_date", Integer.class, storeId))
                .containsExactly(0, 2);
    }

    private void inbound(long storeId, String productCode, int quantity, String expiry) {
        inventoryCommandService.handle(new InboundMessage(UUID.randomUUID().toString(), LATE, storeId, "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(expiry)))));
    }

    private long product(String productCode) {
        jdbcTemplate.update("INSERT INTO product (product_code, name, category, price) VALUES (?, ?, ?, ?)",
                productCode, "마감세일 " + productCode, "테스트", 1500L);
        return jdbcTemplate.queryForObject("SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }
}
