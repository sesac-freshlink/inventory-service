package com.freshlink.inventory.domain.recommendation;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "추천상품 여러 개를 한 번에 켜거나 끈다. productIds는 1~100개, recommended가 true면 켜고 false면 끈다")
public record BulkRecommendationRequest(
        @NotEmpty @Size(max = 100) List<@NotNull Long> productIds,
        @NotNull Boolean recommended
) {
}
