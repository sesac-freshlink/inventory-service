package com.freshlink.inventory.domain.inventory;

import io.swagger.v3.oas.annotations.media.Schema;

/** 점주 설정 목록(추천·선착순)에 붙이는 지금 재고. 재고 목록(S-10)과 같은 계산이다 */
@Schema(description = "지금 재고. availableQuantity = 판매 가능, safetyStock = 안전재고, lowStock = 판매 가능 < 안전재고, brand = 상품 브랜드(없으면 null)")
public record ProductStock(int availableQuantity, int safetyStock, boolean lowStock, String brand) {

    static ProductStock of(InventorySummaryResponse summary) {
        return new ProductStock(summary.availableQuantity(), summary.safetyStock(), summary.lowStock(),
                summary.brand());
    }
}
