package com.freshlink.inventory.domain.inventory;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "S-10 재고 목록 한 페이지. page는 0부터. items는 상품명 순. "
        + "lowStockCount는 검색·필터·페이지와 상관없이 점포 전체에서 안전재고보다 적은 상품 수(화면 위쪽 건수)")
public record InventoryPageResponse(
        List<InventorySummaryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        long lowStockCount
) {
}
