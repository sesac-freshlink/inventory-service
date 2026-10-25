package com.freshlink.inventory.domain.disposal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record DisposalRequest(
        @NotNull Long lotId,
        @NotNull @Min(1) Integer quantity
) {
}
