package com.freshlink.inventory.domain.adjustment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-14 lot 하나의 수동 조정. delta는 증감(−999~999, 0 제외). 실사면 (센 수량 − 지금 수량)")
public record AdjustmentRequest(
        @NotNull
        Long lotId,

        @NotNull
        @Min(-999)
        @Max(999)
        Integer delta,

        @NotNull
        AdjustmentReason reason,

        @Size(max = 200)
        String memo
) {
}
