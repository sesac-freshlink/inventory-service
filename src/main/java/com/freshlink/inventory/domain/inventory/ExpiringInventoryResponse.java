package com.freshlink.inventory.domain.inventory;

import java.time.OffsetDateTime;

public record ExpiringInventoryResponse(
        Long lotId,
        Long productId,
        String productName,
        OffsetDateTime expiryDate,
        boolean expired,
        int quantity,
        int reservedQuantity,
        int disposableQuantity
) {
}