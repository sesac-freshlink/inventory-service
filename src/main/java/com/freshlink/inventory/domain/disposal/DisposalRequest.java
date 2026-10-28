package com.freshlink.inventory.domain.disposal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-32 폐기 요청. lot 하나와 수량(1 이상, 폐기 가능 수량 이하)")
public record DisposalRequest(
        @NotNull Long lotId,
        @NotNull @Min(1) Integer quantity
) {
}
