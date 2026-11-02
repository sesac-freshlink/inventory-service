package com.freshlink.inventory.domain.product;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "U-10 고객용 점포 상품 한 줄. availableQuantity 0이면 soldOut. imageUrl은 없으면 null. "
        + "recommended는 점주가 고른 추천상품, newArrival은 그 점포에 처음 입고된 지 7일 안인 신상품, "
        + "firstCome은 점주가 정한 선착순 상품(구매는 대기열을 거친다). "
        + "nearestExpiry는 먼저 받게 될 상품의 유통기한(상세와 같은 값, 남은 재고가 있는 미경과 lot 중 가장 빠른 기한). 품절이면 null")
public record StoreProductResponse(
        Long productId,
        String name,
        String category,
        long price,
        String imageUrl,
        int availableQuantity,
        boolean soldOut,
        boolean recommended,
        boolean newArrival,
        boolean firstCome,
        OffsetDateTime nearestExpiry
) {
}