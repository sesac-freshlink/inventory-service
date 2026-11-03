package com.freshlink.inventory.domain.inventory;

import java.time.LocalDateTime;

/** 점포에 상품이 처음 입고된 시각(원장 INBOUND의 가장 이른 시각). U-10 신상품 표시에 쓴다 */
public record ProductFirstInbound(Long productId, LocalDateTime firstInboundAt) {
}
