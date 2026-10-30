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
}
