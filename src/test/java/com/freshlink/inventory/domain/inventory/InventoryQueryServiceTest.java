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

import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;
import com.freshlink.inventory.domain.product.StoreProductResponse;
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
    void 고객_상품목록은_상품전체를_반환하고_경과lot은_구매가능수량에서_제외한다() {
        Product ramen = mock(Product.class);
        when(ramen.getId()).thenReturn(15L);
        when(ramen.getName()).thenReturn("매운 컵라면 115g");
        when(ramen.getCategory()).thenReturn("라면");
        when(ramen.getPrice()).thenReturn(1700L);

        Product water = mock(Product.class);
        when(water.getId()).thenReturn(2L);
        when(water.getName()).thenReturn("생수 500ml");
        when(water.getCategory()).thenReturn("음료");
        when(water.getPrice()).thenReturn(1000L);

        Product chips = mock(Product.class);
        when(chips.getId()).thenReturn(8L);
        when(chips.getName()).thenReturn("감자칩");
        when(chips.getCategory()).thenReturn("과자");
        when(chips.getPrice()).thenReturn(1500L);

        // now = 2026-10-20 15:00 KST

        // 컵라면: 유효 lot 8개 중 3개 사용 불가 → 구매 가능 5
        InventoryLot ramenAvailable = lot(
                15L,
                LocalDateTime.of(2026, 10, 20, 16, 0),
                8,
                3
        );

        InventoryLot ramenExpired = mock(InventoryLot.class);
        when(ramenExpired.getProductId()).thenReturn(15L);
        when(ramenExpired.getExpiryDate())
                .thenReturn(LocalDateTime.of(2026, 10, 20, 14, 0));

        InventoryLot waterExpired = mock(InventoryLot.class);
        when(waterExpired.getProductId()).thenReturn(2L);
        when(waterExpired.getExpiryDate())
                .thenReturn(LocalDateTime.of(2026, 10, 20, 15, 0));

        // 감자칩: 이 점포에 lot 자체가 없음

        when(inventoryLotRepository.findByStoreId(1L))
                .thenReturn(List.of(
                        ramenAvailable,
                        ramenExpired,
                        waterExpired
                ));

        when(productRepository.findAll())
                .thenReturn(List.of(ramen, water, chips));

        List<StoreProductResponse> result =
                service.getStoreProducts(1L);

        assertThat(result).hasSize(3);

        // 상품명 오름차순
        assertThat(result)
                .extracting(StoreProductResponse::name)
                .containsExactly(
                        "감자칩",
                        "매운 컵라면 115g",
                        "생수 500ml"
                );

        StoreProductResponse chipsResponse = result.get(0);
        assertThat(chipsResponse.availableQuantity()).isZero();
        assertThat(chipsResponse.soldOut()).isTrue();

        StoreProductResponse ramenResponse = result.get(1);
        assertThat(ramenResponse.availableQuantity()).isEqualTo(5);
        assertThat(ramenResponse.soldOut()).isFalse();

        StoreProductResponse waterResponse = result.get(2);
        assertThat(waterResponse.availableQuantity()).isZero();
        assertThat(waterResponse.soldOut()).isTrue();
    }

    @Test
    void 점포lot이_하나도_없어도_고객_상품목록은_상품전체를_품절로_반환한다() {
        Product ramen = mock(Product.class);
        when(ramen.getId()).thenReturn(15L);
        when(ramen.getName()).thenReturn("매운 컵라면 115g");
        when(ramen.getCategory()).thenReturn("라면");
        when(ramen.getPrice()).thenReturn(1700L);

        Product water = mock(Product.class);
        when(water.getId()).thenReturn(2L);
        when(water.getName()).thenReturn("생수 500ml");
        when(water.getCategory()).thenReturn("음료");
        when(water.getPrice()).thenReturn(1000L);

        when(inventoryLotRepository.findByStoreId(999L))
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
}