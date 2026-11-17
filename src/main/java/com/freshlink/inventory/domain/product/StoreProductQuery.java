package com.freshlink.inventory.domain.product;

/**
 * U-10 고객 상품 목록 조건. 모두 선택이다.
 *
 * @param q               상품명 검색어
 * @param category        이 카테고리만 (정확히 같은 값)
 * @param sort            정렬. null이면 상품명 순
 * @param recommendedOnly 점주가 고른 추천상품만
 * @param newOnly         신상품(그 점포에 처음 입고된 지 7일 안)만
 * @param clearanceOnly   지금 마감세일로 살 수 있는 상품만(마감세일 수량 1 이상)
 * @param firstComeOnly   점주가 선착순을 켠 상품만
 */
public record StoreProductQuery(
        String q,
        String category,
        StoreProductSort sort,
        boolean recommendedOnly,
        boolean newOnly,
        boolean clearanceOnly,
        boolean firstComeOnly
) {

    public static StoreProductQuery of(String q) {
        return new StoreProductQuery(q, null, null, false, false);
    }

    /** 마감세일·선착순 조건 없이 */
    public StoreProductQuery(String q, String category, StoreProductSort sort, boolean recommendedOnly, boolean newOnly) {
        this(q, category, sort, recommendedOnly, newOnly, false, false);
    }

    public StoreProductQuery {
        if (category != null && category.isBlank()) {
            category = null;
        }
        if (sort == null) {
            sort = StoreProductSort.NAME;
        }
    }
}
