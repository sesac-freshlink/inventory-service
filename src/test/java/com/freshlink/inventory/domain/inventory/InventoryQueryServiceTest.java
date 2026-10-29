package com.freshlink.inventory.domain.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;
import com.freshlink.inventory.domain.product.StoreProductResponse;
import com.freshlink.inventory.domain.storestock.StoreProductRow;
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
    StoreProductStockRepository storeProductStockRepository;

    @Mock
    InventorySearchRepository inventorySearchRepository;

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
                storeProductStockRepository,
                inventorySearchRepository,
                clock
        );
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
    void 고객_상품목록은_조회표_JOIN_결과를_그대로_쓰고_값이_맞는_시각이_지났거나_행이_없으면_품절이다() {
        // now = 2026-10-20 15:00 KST
        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 15, 0);
        when(storeProductStockRepository.findStoreProducts(1L, null)).thenReturn(List.of(
                new StoreProductRow(8L, "감자칩", "과자", 1500L, null, null, null),
                new StoreProductRow(15L, "매운 컵라면 115g", "라면", 1700L, "https://img.example/P015.jpg",
                        5, now.plusHours(1)),
                new StoreProductRow(2L, "생수 500ml", "음료", 1000L, null, 3, now)
        ));

        List<StoreProductResponse> result = service.getStoreProducts(1L, null);

        // 순서는 쿼리(상품명 순) 그대로
        assertThat(result)
                .extracting(StoreProductResponse::name, StoreProductResponse::availableQuantity,
                        StoreProductResponse::soldOut, StoreProductResponse::imageUrl)
                .containsExactly(
                        tuple("감자칩", 0, true, null),
                        tuple("매운 컵라면 115g", 5, false, "https://img.example/P015.jpg"),
                        // 값이 맞는 시각(15:00)이 지금과 같으면 이미 지난 것으로 본다
                        tuple("생수 500ml", 0, true, null)
                );
    }

    @Test
    void 검색어는_앞뒤_공백을_빼고_LIKE_조건으로_넘기며_빈_검색어는_조건이_없다() {
        when(storeProductStockRepository.findStoreProducts(1L, "%우유%")).thenReturn(List.of());
        when(storeProductStockRepository.findStoreProducts(1L, null)).thenReturn(List.of());

        service.getStoreProducts(1L, "  우유 ");
        service.getStoreProducts(1L, "   ");

        verify(storeProductStockRepository).findStoreProducts(1L, "%우유%");
        verify(storeProductStockRepository).findStoreProducts(1L, null);
    }
}
