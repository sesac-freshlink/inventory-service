package com.freshlink.inventory.domain.inventory;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-30 임박·경과 lot 한 줄. disposableQuantity = 실물 − 예약")
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