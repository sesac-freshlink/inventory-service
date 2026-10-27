package com.freshlink.inventory.domain.adjustment;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-14 조정 이력 한 줄")
public record AdjustmentHistoryResponse(
        Long adjustmentId,
        Long lotId,
        Long productId,
        String productName,
        OffsetDateTime expiryDate,
        int delta,
        int quantityBefore,
        int quantityAfter,
        AdjustmentReason reason,
        String memo,
        OffsetDateTime createdAt
) {
}
