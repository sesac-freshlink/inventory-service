package com.freshlink.inventory.domain.clearance;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 분류 하나의 마감세일 설정 저장. 범위는 DB CHECK와 같다. 시작 &lt; 끝은 서비스가 본다(INVALID_CLEARANCE_SETTING) */
public record ClearanceSettingRequest(
        @NotBlank String category,
        @NotNull Boolean enabled,
        @NotNull @Min(ClearanceSetting.MIN_DISCOUNT_PERCENT) @Max(ClearanceSetting.MAX_DISCOUNT_PERCENT) Integer discountPercent,
        @NotNull @Min(0) @Max(23) Integer saleStartHour,
        @NotNull @Min(1) @Max(24) Integer saleEndHour
) {
}
