package com.freshlink.inventory.domain.stats;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-42 상품 하나의 기간 통계. 입고·판매·폐기는 양수, adjustedQuantity는 수동 조정(S-14) 합계(±). "
        + "disposalRate = 폐기 ÷ (판매 + 폐기), 판매·폐기가 없으면 null")
public record ProductStatsResponse(
        Long productId,
        String productCode,
        String name,
        String category,
        int inboundQuantity,
        int soldQuantity,
        int disposedQuantity,
        int adjustedQuantity,
        Double disposalRate
) {
}
