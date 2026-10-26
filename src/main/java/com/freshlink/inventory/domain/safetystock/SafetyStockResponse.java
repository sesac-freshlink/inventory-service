package com.freshlink.inventory.domain.safetystock;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-40 상품 하나의 안전재고. customized가 false면 점주가 정하지 않아 기본값 5다")
public record SafetyStockResponse(
        Long productId,
        String productCode,
        String name,
        String category,
        int safetyStock,
        boolean customized
) {
}
