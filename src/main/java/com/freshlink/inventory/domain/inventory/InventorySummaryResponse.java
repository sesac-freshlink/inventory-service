package com.freshlink.inventory.domain.inventory;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-10 재고 목록 한 줄. brand는 상품 브랜드(없으면 null). 상품별 총 수량·예약 수량·구매 가능 수량(경과 lot 제외). "
        + "lowStock은 구매 가능 수량이 안전재고(S-40)보다 적을 때 true. hasMemo는 점주 상품 메모가 있으면 true. "
        + "dailySales는 최근 7일 순판매(판매 − 환불)의 하루 평균(소수 첫째 자리), "
        + "daysOfStock은 지금 구매 가능 수량이 그 속도로 며칠 버티는지(내림). 최근 7일 판매가 없으면 null. "
        + "clearanceQuantity는 지금 마감세일로 살 수 있는 수량(고객 상품 목록과 같은 계산, 점포가 분류마다 정한 판매 시간 안·마감세일을 끈 분류와 선착순 상품 제외, 아니면 0)")
public record InventorySummaryResponse(
        Long productId,
        String productCode,
        String name,
        String category,
        String brand,
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
        boolean hasMemo,
        double dailySales,
        Integer daysOfStock,
        int clearanceQuantity
) {

    /** 재고 소진 예상: 최근 판매 기간(일)의 순판매로 하루 평균과 남은 일수를 채운 복사본 */
    public InventorySummaryResponse withSalesPace(int netSold, int days) {
        double perDay = netSold <= 0 ? 0.0 : (double) netSold / days;
        Integer left = perDay == 0.0 ? null : (int) Math.floor(availableQuantity / perDay);
        return new InventorySummaryResponse(productId, productCode, name, category, brand, price, imageUrl,
                totalQuantity, reservedQuantity, availableQuantity, nearestExpiry, imminentLotCount, expiredLotCount,
                safetyStock, lowStock, hasMemo, Math.round(perDay * 10) / 10.0, left, clearanceQuantity);
    }

    /** 마감세일 수량을 채운 복사본 (StoreProductService.clearanceQuantities) */
    public InventorySummaryResponse withClearanceQuantity(int quantity) {
        return new InventorySummaryResponse(productId, productCode, name, category, brand, price, imageUrl,
                totalQuantity, reservedQuantity, availableQuantity, nearestExpiry, imminentLotCount, expiredLotCount,
                safetyStock, lowStock, hasMemo, dailySales, daysOfStock, quantity);
    }
}
