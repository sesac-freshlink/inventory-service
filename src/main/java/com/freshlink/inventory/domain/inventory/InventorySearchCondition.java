package com.freshlink.inventory.domain.inventory;

import java.util.Set;

/**
 * S-10 점주 재고 목록 검색 조건.
 *
 * @param q        상품명이나 상품 코드에 들어간 글자. 비어 있으면 조건 없음
 * @param statuses 모두 만족해야 하는 상태. 비어 있으면 조건 없음
 * @param memoOnly true면 점주 상품 메모가 있는 상품만
 */
public record InventorySearchCondition(
        String q,
        Set<InventoryStatusFilter> statuses,
        boolean memoOnly
) {

    public InventorySearchCondition {
        q = q == null || q.isBlank() ? null : q.strip();
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
    }

    /** 조건 없음. 점포 전체 */
    public static InventorySearchCondition all() {
        return new InventorySearchCondition(null, Set.of(), false);
    }

    /** 위쪽 "부족 n개"를 셀 때. 검색어·다른 필터와 상관없이 점포 전체의 부족 상품 */
    static InventorySearchCondition lowStockOnly() {
        return new InventorySearchCondition(null, Set.of(InventoryStatusFilter.LOW_STOCK), false);
    }
}
