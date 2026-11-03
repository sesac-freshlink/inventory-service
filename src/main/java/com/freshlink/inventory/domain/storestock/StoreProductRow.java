package com.freshlink.inventory.domain.storestock;

import java.time.LocalDateTime;

/**
 * U-10 고객 상품 목록 한 줄을 만들 재료. 상품과 그 점포의 조회 표 행을 LEFT JOIN 한 결과라
 * 조회 표에 행이 없는 상품은 availableQuantity·validUntil이 null이다
 */
public record StoreProductRow(
        Long productId,
        String name,
        String category,
        Long price,
        String imageUrl,
        Integer availableQuantity,
        LocalDateTime validUntil
) {

    /** 지금 고객에게 보여 줄 구매 가능 수량. 규칙은 StoreProductStock.availableAt과 같다 */
    public int availableAt(LocalDateTime now) {
        return StoreProductStock.availableAt(availableQuantity, validUntil, now);
    }
}
