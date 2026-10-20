package com.freshlink.inventory.domain.inventory;

import java.time.OffsetDateTime;

public record InventoryLotResponse(
        Long lotId,
        OffsetDateTime expiryDate,
        int quantity,
        int reservedQuantity,
        int availableQuantity,
        InventoryLotStatus status
) {
}