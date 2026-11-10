package com.freshlink.inventory.domain.product;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "U-10 고객용 점포 상품 한 줄. availableQuantity 0이면 soldOut. imageUrl은 없으면 null. "
        + "recommended는 점주가 고른 추천상품, newArrival은 그 점포에 처음 입고된 지 7일 안인 신상품, "
        + "firstCome은 점주가 정한 선착순 상품(구매는 대기열을 거친다). "
        + "availableQuantity는 기본 주문으로 살 수 있는 수량(24시간 안 lot은 마감세일로만 판다, 선착순 상품은 예외), "
        + "nearestExpiry는 기본 주문으로 먼저 받게 될 상품의 유통기한(없으면 null). "
        + "clearanceQuantity·clearancePrice·clearanceExpiry는 마감세일(24시간 안 lot, 30% 할인 10원 내림, 06~23시) 수량·단가·기한, "
        + "마감세일이 없으면 0·null·null. soldOut은 기본·마감세일 둘 다 0일 때")
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
        OffsetDateTime nearestExpiry,
        int clearanceQuantity,
        Long clearancePrice,
        OffsetDateTime clearanceExpiry
) {
}