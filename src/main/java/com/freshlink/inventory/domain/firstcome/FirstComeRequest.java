package com.freshlink.inventory.domain.firstcome;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 선착순 켜기·한도 바꾸기. 한도는 1~999 (DB CHECK와 같다) */
public record FirstComeRequest(
        @NotNull @Min(1) @Max(999) Integer limit
) {
}
