package com.freshlink.inventory.domain.inventory;

import java.time.OffsetDateTime;

public record InventorySummaryResponse(
        Long productId,
        String productCode,
        String name,
        String category,
        Long price,
        int totalQuantity,
        int reservedQuantity,
        int availableQuantity,
        OffsetDateTime nearestExpiry,
        int imminentLotCount,
        int expiredLotCount
) {
}