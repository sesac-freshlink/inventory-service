package com.freshlink.inventory.domain.firstcome;

import com.freshlink.inventory.domain.inventory.ProductStock;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "점주 선착순 설정 한 줄. 상품 전체가 나오고, firstCome이 false면 limit·used·remaining은 null. "
        + "stock은 지금 재고(판매 가능·안전재고·부족), 이 점포에 입고된 적 없는 상품은 null")
public record FirstComeSettingResponse(
        Long productId,
        String productCode,
        String name,
        String category,
        boolean firstCome,
        Integer limit,
        Integer used,
        Integer remaining,
        ProductStock stock
) {
}
