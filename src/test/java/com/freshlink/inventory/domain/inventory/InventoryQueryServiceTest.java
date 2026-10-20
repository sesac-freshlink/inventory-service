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
}