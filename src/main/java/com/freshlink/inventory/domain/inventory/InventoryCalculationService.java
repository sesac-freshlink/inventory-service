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
        LocalDateTime now = LocalDateTime.now(clock);

        return lots.stream()
                .filter(lot -> lot.getExpiryDate().isAfter(now))
                .mapToInt(lot -> lot.getQuantity() - lot.getReservedQuantity())
                .sum();
    }

    public InventoryLotStatus calculateStatus(InventoryLot lot) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiryDate = lot.getExpiryDate();

        if (!expiryDate.isAfter(now)) {
            return InventoryLotStatus.EXPIRED;
        }

        if (!expiryDate.isAfter(now.plusHours(24))) {
            return InventoryLotStatus.IMMINENT;
        }

        return InventoryLotStatus.NORMAL;
    }
}