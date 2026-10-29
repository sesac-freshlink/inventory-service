package com.freshlink.inventory.domain.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.freshlink.inventory.domain.memo.ProductMemoRepository;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;
import com.freshlink.inventory.domain.product.StoreProductResponse;
import com.freshlink.inventory.domain.safetystock.SafetyStockRepository;
import com.freshlink.inventory.domain.storestock.StoreProductStock;
import com.freshlink.inventory.domain.storestock.StoreProductStockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class InventoryQueryServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant FIXED_INSTANT =
            Instant.parse("2026-10-20T06:00:00Z");

    @Mock
    InventoryLotRepository inventoryLotRepository;

    @Mock
    ProductRepository productRepository;

    @Mock
    SafetyStockRepository safetyStockRepository;

    @Mock
    StoreProductStockRepository storeProductStockRepository;

    @Mock
    ProductMemoRepository productMemoRepository;

    private InventoryQueryService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_INSTANT, SEOUL);
        InventoryCalculationService calculationService =
                new InventoryCalculationService(clock);

        service = new InventoryQueryService(
                inventoryLotRepository,
                productRepository,
                calculationService,
                safetyStockRepository,
                storeProductStockRepository,
                productMemoRepository,
                clock
        );
    }

    @Test
    void 재고목록은_모든lot을_합산하고_경과lot은_구매가능수량에서_제외한다() {
        Product product = product(
                15L,
                "P015",
                "매운 컵라면 115g",
                "라면",
                1700L
        );

        InventoryLot expiredLot = lot(
                15L,
                LocalDateTime.of(2026, 10, 20, 14, 0),
                10,
                2
        );

        InventoryLot imminentLot = lot(
                15L,
                LocalDateTime.of(2026, 10, 20, 16, 0),
                8,
                3
        );

        when(inventoryLotRepository.findByStoreId(1L))
                .thenReturn(List.of(expiredLot, imminentLot));

        when(productRepository.findAllById(any()))
                .thenReturn(List.of(product));

        List<InventorySummaryResponse> result =
                service.getInventory(1L);

        assertThat(result).hasSize(1);

        InventorySummaryResponse response = result.getFirst();

        assertThat(response.productId()).isEqualTo(15L);
        assertThat(response.totalQuantity()).isEqualTo(18);
        assertThat(response.reservedQuantity()).isEqualTo(5);
        assertThat(response.availableQuantity()).isEqualTo(5);
        assertThat(response.nearestExpiry())
                .isEqualTo("2026-10-20T14:00+09:00");
        assertThat(response.imminentLotCount()).isEqualTo(1);
        assertThat(response.expiredLotCount()).isEqualTo(1);
    }

    @Test
    void 안전재고를_정하지_않은_상품은_기본값5로_부족을_판단한다() {
        Product product = product(15L, "P015", "매운 컵라면 115g", "라면", 1700L);

        InventoryLot lot = lot(15L, LocalDateTime.of(2026, 10, 28, 23, 59), 4, 0);

        when(inventoryLotRepository.findByStoreId(1L)).thenReturn(List.of(lot));
        when(productRepository.findAllById(any())).thenReturn(List.of(product));
        when(safetyStockRepository.quantitiesByProduct(1L)).thenReturn(Map.of());

        InventorySummaryResponse response = service.getInventory(1L).getFirst();

        assertThat(response.safetyStock()).isEqualTo(5);
        assertThat(response.lowStock()).isTrue();
    }

    @Test
    void 점주가_정한_안전재고보다_구매가능수량이_적을때만_부족이다() {
        Product ramen = product(15L, "P015", "매운 컵라면 115g", "라면", 1700L);
        Product milk = product(16L, "P016", "흰 우유 200ml", "유제품", 1200L);

        InventoryLot ramenLot = lot(15L, LocalDateTime.of(2026, 10, 28, 23, 59), 4, 0);
        InventoryLot milkLot = lot(16L, LocalDateTime.of(2026, 10, 28, 23, 59), 10, 0);

        when(inventoryLotRepository.findByStoreId(1L)).thenReturn(List.of(ramenLot, milkLot));
        when(productRepository.findAllById(any())).thenReturn(List.of(ramen, milk));
        // 라면은 0(알림 끔), 우유는 10(구매 가능 10과 같으면 부족 아님)
        when(safetyStockRepository.quantitiesByProduct(1L)).thenReturn(Map.of(15L, 0, 16L, 10));

        Map<Long, InventorySummaryResponse> byProduct = service.getInventory(1L).stream()
                .collect(Collectors.toMap(InventorySummaryResponse::productId, Function.identity()));

        assertThat(byProduct.get(15L).safetyStock()).isZero();
        assertThat(byProduct.get(15L).lowStock()).isFalse();
        assertThat(byProduct.get(16L).safetyStock()).isEqualTo(10);
        assertThat(byProduct.get(16L).lowStock()).isFalse();
    }

    private Product product(
            Long id,
            String productCode,
            String name,
            String category,
            Long price
    ) {
        Product product = mock(Product.class);

        when(product.getId()).thenReturn(id);
        when(product.getProductCode()).thenReturn(productCode);
        when(product.getName()).thenReturn(name);
        when(product.getCategory()).thenReturn(category);
        when(product.getPrice()).thenReturn(price);

        return product;
    }

    private InventoryLot lot(
            Long productId,
            LocalDateTime expiryDate,
            int quantity,
            int reservedQuantity
    ) {
        InventoryLot lot = mock(InventoryLot.class);

        when(lot.getProductId()).thenReturn(productId);
        when(lot.getExpiryDate()).thenReturn(expiryDate);
        when(lot.getQuantity()).thenReturn(quantity);
        when(lot.getReservedQuantity()).thenReturn(reservedQuantity);

        return lot;
    }

    @Test
    void 임박목록은_현재시각을_기준으로_경과와_24시간이내lot만_반환한다() {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(15L);
        when(product.getName()).thenReturn("매운 컵라면 115g");

        // now = 2026-10-20 15:00 KST
        InventoryLot expiredLot = expiringLot(
                101L,
                15L,
                LocalDateTime.of(2026, 10, 20, 14, 0),
                10,
                2
        );

        // expiry == now → 경과
        InventoryLot boundaryExpiredLot = expiringLot(
                102L,
                15L,
                LocalDateTime.of(2026, 10, 20, 15, 0),
                7,
                1
        );

        // now < expiry < now + 24h → 임박
        InventoryLot imminentLot = expiringLot(
                103L,
                15L,
                LocalDateTime.of(2026, 10, 20, 16, 0),
                8,
                3
        );

        // expiry == now + 24h → 임박
        InventoryLot boundaryImminentLot = expiringLot(
                104L,
                15L,
                LocalDateTime.of(2026, 10, 21, 15, 0),
                5,
                0
        );

        // 24시간 초과 → 목록 제외
        InventoryLot normalLot = mock(InventoryLot.class);
        when(normalLot.getProductId()).thenReturn(15L);
        when(normalLot.getQuantity()).thenReturn(9);
        when(normalLot.getExpiryDate())
                .thenReturn(LocalDateTime.of(2026, 10, 21, 15, 0, 1));

        when(inventoryLotRepository.findByStoreId(1L))
                .thenReturn(List.of(
                        normalLot,
                        imminentLot,
                        expiredLot,
                        boundaryImminentLot,
                        boundaryExpiredLot
                ));

        when(productRepository.findAllById(any()))
                .thenReturn(List.of(product));

        List<ExpiringInventoryResponse> result =
                service.getExpiring(1L);

        assertThat(result)
                .extracting(ExpiringInventoryResponse::lotId)
                .containsExactly(101L, 102L, 103L, 104L);

        assertThat(result)
                .extracting(ExpiringInventoryResponse::expired)
                .containsExactly(true, true, false, false);

        assertThat(result.getFirst().disposableQuantity())
                .isEqualTo(8);

        assertThat(result.get(2).disposableQuantity())
                .isEqualTo(5);
    }

    private InventoryLot expiringLot(
            Long id,
            Long productId,
            LocalDateTime expiryDate,
            int quantity,
            int reservedQuantity
    ) {
        InventoryLot lot = mock(InventoryLot.class);

        when(lot.getId()).thenReturn(id);
        when(lot.getProductId()).thenReturn(productId);
        when(lot.getExpiryDate()).thenReturn(expiryDate);
        when(lot.getQuantity()).thenReturn(quantity);
        when(lot.getReservedQuantity()).thenReturn(reservedQuantity);

        return lot;
    }

    @Test
    void 고객_상품목록은_상품전체를_반환하고_구매가능수량은_조회표에서_읽는다() {
        Product ramen = product(15L, "매운 컵라면 115g", "라면", 1700L);
        Product water = product(2L, "생수 500ml", "음료", 1000L);
        Product chips = product(8L, "감자칩", "과자", 1500L);

        // 값이 맞는 시각이 지났는지는 조회 표 행이 판단한다(StoreProductStock.availableAt)
        StoreProductStock ramenStock = stock(15L, 5);
        StoreProductStock waterStock = stock(2L, 0);
        // 감자칩: 이 점포 조회 표에 행 자체가 없음
        when(storeProductStockRepository.findByStoreId(1L))
                .thenReturn(List.of(ramenStock, waterStock));
        when(productRepository.findAll())
                .thenReturn(List.of(ramen, water, chips));

        List<StoreProductResponse> result =
                service.getStoreProducts(1L);

        // 상품명 오름차순
        assertThat(result)
                .extracting(StoreProductResponse::name)
                .containsExactly(
                        "감자칩",
                        "매운 컵라면 115g",
                        "생수 500ml"
                );
        assertThat(result)
                .extracting(StoreProductResponse::availableQuantity, StoreProductResponse::soldOut)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, true),
                        org.assertj.core.groups.Tuple.tuple(5, false),
                        org.assertj.core.groups.Tuple.tuple(0, true)
                );
    }

    @Test
    void 점포에_조회표_행이_하나도_없어도_고객_상품목록은_상품전체를_품절로_반환한다() {
        Product ramen = product(15L, "매운 컵라면 115g", "라면", 1700L);
        Product water = product(2L, "생수 500ml", "음료", 1000L);
        when(storeProductStockRepository.findByStoreId(999L))
                .thenReturn(List.of());
        when(productRepository.findAll())
                .thenReturn(List.of(ramen, water));

        List<StoreProductResponse> result =
                service.getStoreProducts(999L);

        assertThat(result).hasSize(2);
        assertThat(result)
                .allSatisfy(response -> {
                    assertThat(response.availableQuantity()).isZero();
                    assertThat(response.soldOut()).isTrue();
                });
    }

    private static Product product(Long id, String name, String category, Long price) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(id);
        when(product.getName()).thenReturn(name);
        when(product.getCategory()).thenReturn(category);
        when(product.getPrice()).thenReturn(price);
        return product;
    }

    private static StoreProductStock stock(Long productId, int availableNow) {
        StoreProductStock stock = mock(StoreProductStock.class);
        when(stock.getProductId()).thenReturn(productId);
        when(stock.availableAt(any())).thenReturn(availableNow);
        return stock;
    }
}
