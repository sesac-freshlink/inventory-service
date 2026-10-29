package com.freshlink.inventory.domain.inventory;

import java.time.LocalDateTime;

/** 유통기한 달력의 lot 한 줄 재료. lot과 상품을 JOIN 한 결과 */
public record ExpiryLotRow(
        Long lotId,
        Long productId,
        String productCode,
        String name,
        LocalDateTime expiryDate,
        Integer quantity,
        Integer reservedQuantity
) {
}
