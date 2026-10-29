package com.freshlink.inventory.domain.stats;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매 순위 한 줄. 같은 판매 수량이면 같은 순위(1, 2, 2, 4). "
        + "share = 이 상품 판매 ÷ 기간 전체 판매(0~1, 소수 넷째 자리)")
public record SalesRankItem(
        int rank,
        Long productId,
        String productCode,
        String name,
        String category,
        String imageUrl,
        int soldQuantity,
        double share
) {
}
