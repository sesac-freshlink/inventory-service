package com.freshlink.inventory;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.DisposeMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.inventory.domain.adjustment.AdjustmentReason;
import com.freshlink.inventory.domain.adjustment.AdjustmentRequest;
import com.freshlink.inventory.domain.adjustment.AdjustmentService;
import com.freshlink.inventory.domain.disposal.DisposalRequest;
import com.freshlink.inventory.domain.disposal.DisposalResponse;
import com.freshlink.inventory.domain.disposal.DisposalService;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import com.freshlink.inventory.domain.inventory.InventoryQueryService;
import com.freshlink.inventory.domain.inventory.InventorySearchCondition;
import com.freshlink.inventory.domain.inventory.InventorySummaryResponse;
import com.freshlink.inventory.domain.product.StoreProductQuery;
import com.freshlink.inventory.domain.product.StoreProductResponse;
import com.freshlink.inventory.domain.product.StoreProductService;
import com.freshlink.inventory.domain.storestock.StoreStockRefresher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고객용 재고 조회 표(store_product_stock, CQRS). 재고를 바꾸는 처리가 같은 트랜잭션에서 조회 표도 다시 계산하는지,
 * 고객 목록(U-10)이 조회 표만 읽는지, 값이 맞는 시각(valid_until)이 지난 행을 어떻게 다루는지 본다.
 * 지금 시각은 2026-10-25 12:00 KST로 고정한다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class StoreStockTest {

    static final String FRESH = "2027-12-31T23:59:59+09:00";
    static final String IMMINENT = "2026-10-25T23:59:59+09:00";

    @Autowired
    InventoryCommandService inventoryCommandService;

    @Autowired
    InventoryQueryService inventoryQueryService;

    @Autowired
    StoreProductService storeProductService;

    @Autowired
    AdjustmentService adjustmentService;

    @Autowired
    DisposalService disposalService;

    @Autowired
    StoreStockRefresher storeStockRefresher;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void 입고하면_조회표에_구매가능수량과_값이_맞는_시각이_들어간다() {
        long storeId = 4801L;
        long productId = insertProduct("P4801");

        handle(inbound(storeId, "P4801", 3, IMMINENT));
        handle(inbound(storeId, "P4801", 20, FRESH));

        assertThat(row(storeId, productId))
                .containsEntry("available_quantity", 23)
                // 남은 재고가 있는 lot 중 가장 빠른 기한. 이 시각이 지나면 오늘까지 lot 3개가 빠진다
                .containsEntry("valid_until", LocalDateTime.of(2026, 10, 25, 23, 59, 59));
    }

    @Test
    void 예약_해제_확정마다_조회표가_lot에서_계산한_값과_같다() {
        long storeId = 4802L;
        long productId = insertProduct("P4802");
        handle(inbound(storeId, "P4802", 10, FRESH));

        handle(reserve(4802_1L, storeId, productId, 3));
        assertSame(storeId, productId, 7);

        handle(new ReleaseMessage(newId(), NOW, 4802_1L, storeId, ReleaseReason.EXPIRED));
        assertSame(storeId, productId, 10);

        handle(reserve(4802_2L, storeId, productId, 4));
        assertSame(storeId, productId, 6);

        // 확정은 실물과 예약을 같이 줄여서 구매 가능 수량은 그대로다
        handle(new ConfirmMessage(newId(), NOW, 4802_2L, storeId));
        assertSame(storeId, productId, 6);
    }

    @Test
    void 폐기와_수동_조정도_조회표에_반영된다() {
        long storeId = 4803L;
        long productId = insertProduct("P4803");
        handle(inbound(storeId, "P4803", 5, IMMINENT));
        handle(inbound(storeId, "P4803", 10, FRESH));
        long imminentLot = lotId(storeId, IMMINENT);
        long freshLot = lotId(storeId, FRESH);
        assertSame(storeId, productId, 15);

        DisposalResponse disposal = disposalService.request(storeId, "owner-4803", new DisposalRequest(imminentLot, 5));
        handle(DisposeMessage.of(disposal.disposalId(), storeId, imminentLot, 5, "owner-4803"));
        assertSame(storeId, productId, 10);
        // 오늘까지 lot이 비었으므로 값이 맞는 시각은 다음 lot의 기한으로 넘어간다
        assertThat(row(storeId, productId)).containsEntry("valid_until", LocalDateTime.of(2027, 12, 31, 23, 59, 59));

        adjustmentService.adjust(storeId, "owner-4803", new AdjustmentRequest(freshLot, -2, AdjustmentReason.LOSS, null));
        assertSame(storeId, productId, 8);
    }

    @Test
    void 고객_목록은_lot을_합산하지_않고_조회표를_읽는다() {
        long storeId = 4804L;
        long productId = insertProduct("P4804");
        handle(inbound(storeId, "P4804", 10, FRESH));

        // 조회 표만 바꿔 본다. 고객 목록이 이 값을 그대로 보여 주면 lot을 다시 합산하지 않는다는 뜻이다
        jdbcTemplate.update("UPDATE store_product_stock SET available_quantity = 99 WHERE store_id = ? AND product_id = ?",
                storeId, productId);

        assertThat(customerView(storeId, productId).availableQuantity()).isEqualTo(99);
        assertThat(ownerView(storeId, productId).availableQuantity()).isEqualTo(10);
    }

    @Test
    void 값이_맞는_시각이_지난_행은_품절로_보인다() {
        long storeId = 4805L;
        long productId = insertProduct("P4805");
        handle(inbound(storeId, "P4805", 10, FRESH));

        // 시간이 흘러 기한이 지났는데 아직 정리 작업이 돌지 않은 상태
        jdbcTemplate.update("UPDATE store_product_stock SET valid_until = ? WHERE store_id = ? AND product_id = ?",
                NOW.toLocalDateTime(), storeId, productId);

        StoreProductResponse view = customerView(storeId, productId);
        assertThat(view.availableQuantity()).isZero();
        assertThat(view.soldOut()).isTrue();
    }

    @Test
    void 정리_작업은_값이_맞는_시각이_지난_행만_lot에서_다시_계산한다() {
        long storeId = 4806L;
        long productId = insertProduct("P4806");
        handle(inbound(storeId, "P4806", 3, IMMINENT));
        handle(inbound(storeId, "P4806", 20, FRESH));

        // 시간이 흘러 오늘까지 lot의 기한이 지났다
        LocalDateTime passed = NOW.toLocalDateTime().minusHours(1);
        jdbcTemplate.update("UPDATE inventory_lot SET expiry_date = ? WHERE id = ?", passed, lotId(storeId, IMMINENT));
        jdbcTemplate.update("UPDATE store_product_stock SET valid_until = ? WHERE store_id = ? AND product_id = ?",
                passed, storeId, productId);

        int refreshed = storeStockRefresher.refreshStale();

        assertThat(refreshed).isPositive();
        assertThat(row(storeId, productId))
                .containsEntry("available_quantity", 20)
                .containsEntry("valid_until", LocalDateTime.of(2027, 12, 31, 23, 59, 59));
        assertThat(customerView(storeId, productId).availableQuantity()).isEqualTo(20);
    }

    @Test
    void 재고가_모두_빠지면_값이_맞는_시각은_비고_품절이다() {
        long storeId = 4807L;
        long productId = insertProduct("P4807");
        handle(inbound(storeId, "P4807", 2, FRESH));

        handle(reserve(4807_1L, storeId, productId, 2));

        assertThat(row(storeId, productId))
                .containsEntry("available_quantity", 0)
                .containsEntry("valid_until", null);
        assertThat(customerView(storeId, productId).soldOut()).isTrue();
    }

    @Test
    void 마이그레이션은_지금_있는_lot으로_조회표를_미리_채우고_이미_있는_행은_건드리지_않는다() throws Exception {
        long storeId = 4808L;
        long productId = insertProduct("P4808");
        long otherProductId = insertProduct("P4809");
        // 조회 표가 생기기 전에 들어온 lot (쪽지를 거치지 않고 넣어 조회 표 행이 없다)
        insertLotDirectly(storeId, productId, 7, 0, LocalDateTime.of(2027, 12, 31, 23, 59, 59));
        insertLotDirectly(storeId, productId, 4, 0, LocalDateTime.of(2026, 1, 1, 0, 0));
        insertLotDirectly(storeId, otherProductId, 5, 0, LocalDateTime.of(2027, 12, 31, 23, 59, 59));
        jdbcTemplate.update("""
                INSERT INTO store_product_stock (store_id, product_id, available_quantity, valid_until, updated_at)
                VALUES (?, ?, 99, NULL, NOW(3))
                """, storeId, otherProductId);

        jdbcTemplate.update(migrationBackfill());

        assertThat(row(storeId, productId))
                .containsEntry("available_quantity", 7)
                .containsEntry("valid_until", LocalDateTime.of(2027, 12, 31, 23, 59, 59));
        assertThat(row(storeId, otherProductId)).containsEntry("available_quantity", 99);
    }

    /**
     * 잠금 읽기(FOR SHARE)가 왜 필요한지. 조정 트랜잭션이 먼저 무언가를 읽어 두면(MySQL은 이때 읽기 기준 시점을 잡는다)
     * 그 뒤에 커밋된 다른 처리의 변경은 일반 SELECT로는 보이지 않는다. 그 사이 쪽지 처리가 같은 상품의 다른 lot을 예약하면
     * 조정이 낸 합계에 그 예약이 빠져 조회 표가 낡은 값(14)이 된다. 잠금 읽기는 최신 커밋 값을 읽어 12가 된다
     */
    @Test
    void 수동_조정과_쪽지_처리가_겹쳐도_조회표는_최신_합계다() {
        long storeId = 4810L;
        long productId = insertProduct("P4810");
        handle(inbound(storeId, "P4810", 5, IMMINENT));
        handle(inbound(storeId, "P4810", 10, FRESH));
        long freshLot = lotId(storeId, FRESH);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // 조정 트랜잭션이 먼저 lot 표를 한 번 읽는다 (읽기 기준 시점이 여기서 정해진다)
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inventory_lot WHERE store_id = ?", Integer.class, storeId);

            // 그 사이 다른 스레드의 쪽지 처리가 오늘까지 lot에서 2개를 예약하고 커밋한다 (FEFO라 기한 빠른 lot)
            CompletableFuture.runAsync(() -> handle(reserve(4810_1L, storeId, productId, 2))).join();

            // 조정은 다른 lot(2027년까지 lot)을 1개 줄인다. lot이 달라 낙관적 락 충돌은 없다
            adjustmentService.adjust(storeId, "owner-4810", new AdjustmentRequest(freshLot, -1, AdjustmentReason.LOSS, null));
        });

        // (10 − 1) + (5 − 2) = 12. 예약을 못 보고 합산했다면 (10 − 1) + 5 = 14
        assertSame(storeId, productId, 12);
    }

    private void assertSame(long storeId, long productId, int expected) {
        assertThat(ownerView(storeId, productId).availableQuantity()).isEqualTo(expected);
        assertThat(row(storeId, productId)).containsEntry("available_quantity", expected);
        assertThat(customerView(storeId, productId).availableQuantity()).isEqualTo(expected);
    }

    private void handle(BaseMessage message) {
        inventoryCommandService.handle(message);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static InboundMessage inbound(long storeId, String productCode, int quantity, String expiryDate) {
        return new InboundMessage(newId(), NOW, storeId, "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(expiryDate))));
    }

    private static ReserveMessage reserve(long orderId, long storeId, long productId, int quantity) {
        return new ReserveMessage(newId(), NOW, orderId, storeId, productId, quantity);
    }

    private long insertProduct(String productCode) {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, "조회 표 " + productCode, "테스트", 1500L);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private void insertLotDirectly(long storeId, long productId, int quantity, int reserved, LocalDateTime expiry) {
        jdbcTemplate.update("""
                INSERT INTO inventory_lot
                    (store_id, product_id, expiry_date, quantity, reserved_quantity, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'NORMAL', NOW(3), NOW(3))
                """, storeId, productId, expiry, quantity, reserved);
    }

    private long lotId(long storeId, String expiryDate) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_lot WHERE store_id = ? AND expiry_date = ?",
                Long.class, storeId, OffsetDateTime.parse(expiryDate).toLocalDateTime());
    }

    private Map<String, Object> row(long storeId, long productId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT available_quantity, valid_until
                FROM store_product_stock
                WHERE store_id = ? AND product_id = ?
                """, storeId, productId);
        Object validUntil = row.get("valid_until");
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("available_quantity", ((Number) row.get("available_quantity")).intValue());
        result.put("valid_until", switch (validUntil) {
            case null -> null;
            case java.sql.Timestamp t -> t.toLocalDateTime();
            default -> validUntil;
        });
        return result;
    }

    private StoreProductResponse customerView(long storeId, long productId) {
        return storeProductService.getStoreProducts(storeId, StoreProductQuery.of(null)).stream()
                .filter(p -> p.productId().equals(productId))
                .findFirst()
                .orElseThrow();
    }

    /** 점주 재고 목록(S-10)은 요청 때마다 lot을 SQL로 합산한다. 조회 표와 비교할 기준으로 쓴다 */
    private InventorySummaryResponse ownerView(long storeId, long productId) {
        return inventoryQueryService.searchInventory(storeId, InventorySearchCondition.all(), 0, 100).items().stream()
                .filter(p -> p.productId().equals(productId))
                .findFirst()
                .orElseThrow();
    }

    /** V6 파일의 미리 채우기 문장을 그대로 읽는다 */
    private String migrationBackfill() throws java.io.IOException {
        String sql = new String(
                getClass().getResourceAsStream("/db/migration/V6__store_product_stock.sql").readAllBytes(),
                StandardCharsets.UTF_8);
        String insert = sql.substring(sql.indexOf("INSERT INTO store_product_stock"));
        return insert.substring(0, insert.indexOf(';'));
    }
}
