package com.freshlink.inventory.domain.inventory;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.Pageable;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerQueryServiceTest {

    @Mock
    LedgerRepository ledgerRepository;

    @Mock
    InventoryLotRepository inventoryLotRepository;

    @Mock
    ProductRepository productRepository;

    @InjectMocks
    LedgerQueryService ledgerQueryService;

    @Test
    void 시작일이_종료일보다_늦으면_조회하지_않는다() {
        LocalDate from = LocalDate.of(2026, 10, 23);
        LocalDate to = LocalDate.of(2026, 10, 22);

        assertThatThrownBy(() ->
                ledgerQueryService.getLedger(
                        1L,
                        null,
                        null,
                        from,
                        to
                )
        ).isInstanceOf(BusinessException.class);

        verifyNoInteractions(
                ledgerRepository,
                inventoryLotRepository,
                productRepository
        );
    }

    @Test
    void 날짜를_하루의_시작과_다음날_시작으로_변환한다() {
        when(ledgerRepository.findLedger(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(Pageable.class)
        )).thenReturn(List.of());

        ledgerQueryService.getLedger(
                1L,
                null,
                null,
                LocalDate.of(2026, 10, 22),
                LocalDate.of(2026, 10, 22)
        );

        verify(ledgerRepository).findLedger(
                eq(1L),
                isNull(),
                isNull(),
                eq(LocalDateTime.of(2026, 10, 22, 0, 0)),
                eq(LocalDateTime.of(2026, 10, 23, 0, 0)),
                any(Pageable.class)
        );
    }

    @Test
    void 원장에_lot과_상품정보를_합쳐서_응답한다() {
        Ledger ledger = mock(Ledger.class);
        InventoryLot lot = mock(InventoryLot.class);
        Product product = mock(Product.class);

        when(ledger.getId()).thenReturn(9001L);
        when(ledger.getLotId()).thenReturn(301L);
        when(ledger.getType()).thenReturn(LedgerType.INBOUND);
        when(ledger.getQuantity()).thenReturn(10);
        when(ledger.getRefId()).thenReturn("SHP-001");
        when(ledger.getCreatedAt()).thenReturn(
                LocalDateTime.of(2026, 10, 22, 10, 0, 3)
        );

        when(lot.getId()).thenReturn(301L);
        when(lot.getProductId()).thenReturn(15L);
        when(lot.getExpiryDate()).thenReturn(
                LocalDateTime.of(2026, 10, 30, 23, 59, 59)
        );

        when(product.getId()).thenReturn(15L);
        when(product.getName()).thenReturn("매운 컵라면 115g");

        when(ledgerRepository.findLedger(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(Pageable.class)
        )).thenReturn(List.of(ledger));

        when(inventoryLotRepository.findAllById(any()))
                .thenReturn(List.of(lot));

        when(productRepository.findAllById(any()))
                .thenReturn(List.of(product));

        List<LedgerResponse> result =
                ledgerQueryService.getLedger(
                        1L,
                        null,
                        null,
                        null,
                        null
                );

        assertThat(result).hasSize(1);

        LedgerResponse response = result.getFirst();

        assertThat(response.ledgerId()).isEqualTo(9001L);
        assertThat(response.type()).isEqualTo(LedgerType.INBOUND);
        assertThat(response.productId()).isEqualTo(15L);
        assertThat(response.productName())
                .isEqualTo("매운 컵라면 115g");
        assertThat(response.lotId()).isEqualTo(301L);
        assertThat(response.quantity()).isEqualTo(10);
        assertThat(response.refId()).isEqualTo("SHP-001");

        assertThat(response.expiryDate().getOffset())
                .hasToString("+09:00");
        assertThat(response.createdAt().getOffset())
                .hasToString("+09:00");
    }
}