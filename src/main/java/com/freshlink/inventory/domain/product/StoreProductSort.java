package com.freshlink.inventory.domain.product;

/** U-10 고객 상품 목록 정렬 */
public enum StoreProductSort {
    /** 상품명 순 (기본) */
    NAME,
    /** 추천순: 최근 7일 순판매(판매 − 환불 복원)가 많은 순, 같으면 상품명 순 */
    POPULAR
}
