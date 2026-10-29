package com.freshlink.inventory.domain.inventory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class InventoryCalculationService {

    private final Clock clock;

    public InventoryCalculationService(Clock clock) {
        this.clock = clock;
    }

    public int calculateAvailableQuantity(List<InventoryLot> lots) {
        return calculateAvailableQuantity(lots, LocalDateTime.now(clock));
    }

    int calculateAvailableQuantity(List<InventoryLot> lots, LocalDateTime now) {
        return lots.stream()
                .filter(lot -> lot.getExpiryDate().isAfter(now))
                .mapToInt(lot -> lot.getQuantity() - lot.getReservedQuantity())
                .sum();
    }

    public InventoryLotStatus calculateStatus(InventoryLot lot) {
        return calculateStatus(lot, LocalDateTime.now(clock));
    }

    InventoryLotStatus calculateStatus(InventoryLot lot, LocalDateTime now) {
        return calculateStatus(lot.getExpiryDate(), now);
    }

    /** 경과 = 기한 ≤ 지금, 임박 = 지금 < 기한 ≤ 지금 + 24시간, 나머지 정상 */
    InventoryLotStatus calculateStatus(LocalDateTime expiryDate, LocalDateTime now) {
        if (!expiryDate.isAfter(now)) {
            return InventoryLotStatus.EXPIRED;
        }

        if (!expiryDate.isAfter(now.plusHours(24))) {
            return InventoryLotStatus.IMMINENT;
        }

        return InventoryLotStatus.NORMAL;
    }
}