package com.freshlink.inventory.domain.recommendation;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "점주 추천상품 설정 한 줄. 상품 전체가 나오고 recommended가 true면 고객 목록에 '추천'으로 보인다")
public record ProductRecommendationResponse(
        Long productId,
        String productCode,
        String name,
        String category,
        boolean recommended
) {
}
