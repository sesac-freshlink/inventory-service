package com.freshlink.inventory.domain.disposal;

import com.freshlink.inventory.domain.inventory.InventoryLot;
import com.freshlink.inventory.domain.inventory.InventoryLotRepository;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisposalQueryServiceTest {

    @Mock
    DisposalRepository disposalRepository;

    @Mock
    InventoryLotRepository inventoryLotRepository;

    @Mock
    ProductRepository productRepository;

    @InjectMocks
    DisposalQueryService disposalQueryService;

    @Test
    void 이력이_없으면_빈배열이고_lot과_상품은_읽지_않는다() {
        when(disposalRepository.findByStoreIdOrderByIdDesc(eq(1L), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(disposalQueryService.getDisposals(1L, null)).isEmpty();

        verifyNoInteractions(inventoryLotRepository, productRepository);
    }

    @Test
    void 상태를_주면_그_상태만_조회한다() {
        when(disposalRepository.findByStoreIdAndStatusOrderByIdDesc(
                eq(1L), eq(DisposalStatus.SKIPPED), any(Pageable.class)))
                .thenReturn(List.of());

        disposalQueryService.getDisposals(1L, DisposalStatus.SKIPPED);

        verify(disposalRepository, never()).findByStoreIdOrderByIdDesc(any(), any(Pageable.class));
    }

    @Test
    void 폐기요청에_lot의_상품명과_유통기한을_합쳐서_응답한다() {
        Disposal done = disposal(501L, 412L, 2, DisposalStatus.DONE,
                LocalDateTime.of(2026, 10, 25, 12, 0, 0),
                LocalDateTime.of(2026, 10, 25, 12, 0, 2));
        Disposal requested = disposal(502L, 412L, 1, DisposalStatus.REQUESTED,
                LocalDateTime.of(2026, 10, 25, 12, 5, 0),
                null);

        InventoryLot lot = mock(InventoryLot.class);
        when(lot.getId()).thenReturn(412L);
        when(lot.getProductId()).thenReturn(3L);
        when(lot.getExpiryDate()).thenReturn(LocalDateTime.of(2026, 10, 24, 23, 59, 59));

        Product product = mock(Product.class);
        when(product.getId()).thenReturn(3L);
        when(product.getName()).thenReturn("흰우유 200ml");

        when(disposalRepository.findByStoreIdOrderByIdDesc(eq(1L), any(Pageable.class)))
                .thenReturn(List.of(requested, done));
        when(inventoryLotRepository.findAllById(any())).thenReturn(List.of(lot));
        when(productRepository.findAllById(any())).thenReturn(List.of(product));

        List<DisposalHistoryResponse> result = disposalQueryService.getDisposals(1L, null);

        assertThat(result).hasSize(2);

        DisposalHistoryResponse first = result.getFirst();
        assertThat(first.disposalId()).isEqualTo(502L);
        assertThat(first.status()).isEqualTo(DisposalStatus.REQUESTED);
        assertThat(first.productName()).isEqualTo("흰우유 200ml");
        assertThat(first.productId()).isEqualTo(3L);
        assertThat(first.lotId()).isEqualTo(412L);
        assertThat(first.quantity()).isEqualTo(1);
        assertThat(first.expiryDate().getOffset()).hasToString("+09:00");
        assertThat(first.createdAt().getOffset()).hasToString("+09:00");
        assertThat(first.processedAt()).isNull();

        DisposalHistoryResponse second = result.get(1);
        assertThat(second.disposalId()).isEqualTo(501L);
        assertThat(second.status()).isEqualTo(DisposalStatus.DONE);
        assertThat(second.processedAt()).isNotNull();
        assertThat(second.processedAt().getOffset()).hasToString("+09:00");
    }

    private static Disposal disposal(
            Long id,
            Long lotId,
            int quantity,
            DisposalStatus status,
            LocalDateTime createdAt,
            LocalDateTime processedAt
    ) {
        Disposal disposal = mock(Disposal.class);
        when(disposal.getId()).thenReturn(id);
        when(disposal.getLotId()).thenReturn(lotId);
        when(disposal.getQuantity()).thenReturn(quantity);
        when(disposal.getStatus()).thenReturn(status);
        when(disposal.getCreatedAt()).thenReturn(createdAt);
        when(disposal.getProcessedAt()).thenReturn(processedAt);
        return disposal;
    }
}
