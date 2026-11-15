package com.freshlink.inventory.domain.recommendation;

import com.freshlink.inventory.domain.inventory.ProductStock;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "점주 추천상품 설정 한 줄. 상품 전체가 나오고 recommended가 true면 고객 목록에 '추천'으로 보인다. "
        + "stock은 지금 재고(판매 가능·안전재고·부족), 이 점포에 입고된 적 없는 상품은 null")
public record ProductRecommendationResponse(
        Long productId,
        String productCode,
        String name,
        String category,
        boolean recommended,
        ProductStock stock
) {
}
