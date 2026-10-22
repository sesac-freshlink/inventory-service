package com.freshlink.inventory.domain.inventory;

import java.time.OffsetDateTime;

public record LedgerResponse(
        Long ledgerId,
        LedgerType type,
        Long productId,
        String productName,
        Long lotId,
        OffsetDateTime expiryDate,
        int quantity,
        String refId,
        OffsetDateTime createdAt
) {
}