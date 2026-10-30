package com.freshlink.inventory.domain.product;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "U-10 고객용 점포 상품 한 줄. availableQuantity 0이면 soldOut. imageUrl은 없으면 null. "
        + "recommended는 점주가 고른 추천상품, newArrival은 그 점포에 처음 입고된 지 7일 안인 신상품")
public record StoreProductResponse(
        Long productId,
        String name,
        String category,
        long price,
        String imageUrl,
        int availableQuantity,
        boolean soldOut,
        boolean recommended,
        boolean newArrival
) {
}