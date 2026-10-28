package com.freshlink.inventory.domain.safetystock;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-40 안전재고 변경. 0이면 그 상품은 부족으로 표시하지 않는다")
public record SafetyStockRequest(
        @NotNull
        @Min(0)
        @Max(SafetyStock.MAX_QUANTITY)
        Integer safetyStock
) {
}
