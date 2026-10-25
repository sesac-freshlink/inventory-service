package com.freshlink.inventory.domain.inventory;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-11 lot 한 줄. 상태는 지금 시각으로 계산한 NORMAL/IMMINENT/EXPIRED")
public record InventoryLotResponse(
        Long lotId,
        OffsetDateTime expiryDate,
        int quantity,
        int reservedQuantity,
        int availableQuantity,
        InventoryLotStatus status
) {
}