package com.freshlink.inventory;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.inventory.domain.assistant.StoreSummary;
import com.freshlink.inventory.domain.assistant.StoreSummaryService;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 점주 비서 점포 요약(문서 4-1). 실제 MySQL로 원장 집계를 확인한다. 지금 시각은 2026-10-25 12:00 KST.
 * 점포 4301~4399, 상품 P43xx, 주문 43xx를 쓴다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class StoreSummaryTest {

    static final String NORMAL = "2027-12-31T23:59:59+09:00";
    static final String TODAY_NIGHT = "2026-10-25T23:59:59+09:00";

    @Autowired
    InventoryCommandService inventoryCommandService;

    @Autowired
    StoreSummaryService storeSummaryService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void 그_점포의_재고와_원장만_집계한다() {
        long storeId = 4301L;
        long ramen = product("P4301", "요약 라면");
        long ramenLot = inbound(storeId, "P4301", 10, NORMAL);
        long saladLot = inbound(storeId, "P4302", 4, TODAY_NIGHT);
        product("P4302", "요약 샐러드");
        handle(new ReserveMessage(UUID.randomUUID().toString(), NOW, 4301L, storeId, ramen, 3));
        handle(new ConfirmMessage(UUID.randomUUID().toString(), NOW, 4301L, storeId));
        // 지난달 판매 5개(10/1 이전), 이번 달 오전 판매 2개, 폐기 1개
        ledger(storeId, ramenLot, "SALE", -5, LocalDateTime.of(2026, 9, 20, 9, 0));
        ledger(storeId, ramenLot, "SALE", -2, LocalDateTime.of(2026, 10, 24, 8, 30));
        ledger(storeId, saladLot, "DISPOSE", -1, LocalDateTime.of(2026, 10, 24, 22, 0));
        // 다른 점포 원장은 섞이면 안 된다
        long otherLot = inbound(4302L, "P4301", 7, NORMAL);
        ledger(4302L, otherLot, "SALE", -6, LocalDateTime.of(2026, 10, 24, 12, 0));

        StoreSummary summary = storeSummaryService.build(storeId);

        assertThat(summary.store().storeId()).isEqualTo(storeId);
        StoreSummary.ProductSummary r = summary.products().stream()
                .filter(p -> p.productId() == ramen).findFirst().orElseThrow();
        assertThat(r.available()).isEqualTo(10 - 3 - 2 - 5);
        assertThat(r.soldThisMonth()).isEqualTo(3 + 2);
        assertThat(r.soldLastMonth()).isEqualTo(5);
        assertThat(r.inbound30d()).isEqualTo(10);
        assertThat(r.dailySales14d()).hasSize(14).endsWith(2, 3);
        assertThat(r.salesByHour7d()).containsEntry("07-11", 2).containsEntry("11-14", 3);
        assertThat(summary.lots()).singleElement().satisfies(lot -> {
            assertThat(lot.name()).isEqualTo("요약 샐러드");
            assertThat(lot.quantity()).isEqualTo(3);
            assertThat(lot.status()).isEqualTo("IMMINENT");
            assertThat(lot.expiresAt()).isEqualTo(OffsetDateTime.parse(TODAY_NIGHT));
        });
        StoreSummary.ProductSummary s = summary.products().stream()
                .filter(p -> p.name().equals("요약 샐러드")).findFirst().orElseThrow();
        assertThat(s.disposed30d()).isEqualTo(1);
        assertThat(s.dailySales14d()).isEmpty();
    }

    @Test
    void 요약에는_고객이나_주문_칸이_없다() throws Exception {
        long storeId = 4303L;
        product("P4303", "요약 우유");
        inbound(storeId, "P4303", 2, NORMAL);

        String json = objectMapper.writeValueAsString(storeSummaryService.build(storeId)).toLowerCase();

        assertThat(json).doesNotContain("customer", "member", "orderid", "phone", "email");
    }

    @Test
    void 입고가_없는_점포는_빈_목록이다() {
        StoreSummary summary = storeSummaryService.build(4304L);

        assertThat(summary.products()).isEmpty();
        assertThat(summary.lots()).isEmpty();
    }

    @Test
    void 같은_점포는_10분_동안_만든_요약을_다시_쓴다() {
        long storeId = 4305L;
        product("P4305", "요약 김밥");
        inbound(storeId, "P4305", 2, NORMAL);
        StoreSummary first = storeSummaryService.build(storeId);

        inbound(storeId, "P4305", 3, "2027-11-30T23:59:59+09:00");

        assertThat(storeSummaryService.build(storeId)).isSameAs(first);
    }

    private long product(String productCode, String name) {
        Long existing = jdbcTemplate.query("SELECT id FROM product WHERE product_code = ?",
                rs -> rs.next() ? rs.getLong(1) : null, productCode);
        if (existing != null) {
            jdbcTemplate.update("UPDATE product SET name = ? WHERE id = ?", name, existing);
            return existing;
        }
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, name, "테스트", 1500L);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private long inbound(long storeId, String productCode, int quantity, String expiry) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product WHERE product_code = ?", Integer.class, productCode);
        if (exists == 0) {
            product(productCode, productCode);
        }
        handle(new InboundMessage(
                UUID.randomUUID().toString(),
                NOW,
                storeId,
                "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(expiry)))
        ));
        return jdbcTemplate.queryForObject("""
                SELECT lot.id FROM inventory_lot lot JOIN product p ON p.id = lot.product_id
                WHERE lot.store_id = ? AND p.product_code = ? AND lot.expiry_date = ?
                """, Long.class, storeId, productCode, OffsetDateTime.parse(expiry).toLocalDateTime());
    }

    private void ledger(long storeId, long lotId, String type, int quantity, LocalDateTime createdAt) {
        jdbcTemplate.update("UPDATE inventory_lot SET quantity = quantity + ? WHERE id = ?", quantity, lotId);
        jdbcTemplate.update("""
                INSERT INTO ledger (store_id, lot_id, type, quantity, ref_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, storeId, lotId, type, quantity, "summary-test", createdAt);
    }

    private void handle(BaseMessage message) {
        inventoryCommandService.handle(message);
    }
}
