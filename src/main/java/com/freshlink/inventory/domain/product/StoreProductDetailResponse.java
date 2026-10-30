package com.freshlink.inventory.domain.product;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "U-10 고객 상품 상세. 고객용이라 \"예약\" 관련 값은 없다. "
        + "nearestExpiry는 먼저 받게 될 상품의 유통기한(+09:00): 남은 재고가 있는 미경과 lot 중 가장 빠른 기한이고, "
        + "예약은 기한 빠른 것부터 잡는다(FEFO). 품절이면 null. recommended·newArrival·firstCome은 목록과 같다")
public record StoreProductDetailResponse(
        Long productId,
        String name,
        String category,
        long price,
        String imageUrl,
        int availableQuantity,
        boolean soldOut,
        OffsetDateTime nearestExpiry,
        boolean recommended,
        boolean newArrival,
        boolean firstCome
) {
}
