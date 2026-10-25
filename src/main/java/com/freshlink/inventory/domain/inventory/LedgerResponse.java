package com.freshlink.inventory.domain.inventory;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-13 변동 이력 한 줄. quantity는 부호 포함(입고 +, 판매·폐기 −)")
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