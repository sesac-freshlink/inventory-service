package com.freshlink.inventory.domain.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InventoryCalculationServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant FIXED_INSTANT =
            Instant.parse("2026-10-20T06:00:00Z");

    private InventoryCalculationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_INSTANT, SEOUL);
        service = new InventoryCalculationService(clock);
    }

    @Test
    void 구매가능수량에서_경과lot을_제외한다() {
        InventoryLot expiredLot = lot(
                LocalDateTime.of(2026, 10, 20, 14, 59, 59),
                10,
                2
        );

        InventoryLot futureLot = lot(
                LocalDateTime.of(2026, 10, 20, 15, 0, 1),
                8,
                3
        );

        int result = service.calculateAvailableQuantity(
                List.of(expiredLot, futureLot)
        );

        assertThat(result).isEqualTo(5);
    }

    @Test
    void 유통기한이_현재시각과_같으면_경과다() {
        InventoryLot lot = lot(
                LocalDateTime.of(2026, 10, 20, 15, 0, 0),
                10,
                0
        );

        assertThat(service.calculateStatus(lot))
                .isEqualTo(InventoryLotStatus.EXPIRED);
    }

    @Test
    void 정확히_24시간뒤까지는_임박이다() {
        InventoryLot imminent = lot(
                LocalDateTime.of(2026, 10, 21, 15, 0, 0),
                10,
                0
        );

        InventoryLot normal = lot(
                LocalDateTime.of(2026, 10, 21, 15, 0, 1),
                10,
                0
        );

        assertThat(service.calculateStatus(imminent))
                .isEqualTo(InventoryLotStatus.IMMINENT);

        assertThat(service.calculateStatus(normal))
                .isEqualTo(InventoryLotStatus.NORMAL);
    }

    private InventoryLot lot(
            LocalDateTime expiryDate,
            int quantity,
            int reservedQuantity
    ) {
        InventoryLot lot = mock(InventoryLot.class);

        when(lot.getExpiryDate()).thenReturn(expiryDate);
        when(lot.getQuantity()).thenReturn(quantity);
        when(lot.getReservedQuantity()).thenReturn(reservedQuantity);

        return lot;
    }
}