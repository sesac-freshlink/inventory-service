package com.freshlink.inventory.domain.inventory;

import java.time.LocalDateTime;

/** 유통기한 스케줄러가 상태를 바꿀 lot 하나와 알림 문구에 쓸 상품명 */
public record LotTransitionRow(
        Long lotId,
        Long storeId,
        Long productId,
        String productName,
        LocalDateTime expiryDate,
        int quantity
) {
}
