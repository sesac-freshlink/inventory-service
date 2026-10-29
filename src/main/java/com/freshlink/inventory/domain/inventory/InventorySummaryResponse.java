package com.freshlink.inventory.domain.inventory;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-10 재고 목록 한 줄. 상품별 총 수량·예약 수량·구매 가능 수량(경과 lot 제외). "
        + "lowStock은 구매 가능 수량이 안전재고(S-40)보다 적을 때 true. hasMemo는 점주 상품 메모가 있으면 true")
public record InventorySummaryResponse(
        Long productId,
        String productCode,
        String name,
        String category,
        Long price,
        String imageUrl,
        int totalQuantity,
        int reservedQuantity,
        int availableQuantity,
        OffsetDateTime nearestExpiry,
        int imminentLotCount,
        int expiredLotCount,
        int safetyStock,
        boolean lowStock,
        boolean hasMemo
) {
}
