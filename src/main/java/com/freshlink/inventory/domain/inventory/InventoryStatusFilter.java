package com.freshlink.inventory.domain.inventory;

/** S-10 점주 재고 목록의 상태 필터. 여러 개를 주면 모두 만족하는 상품만 남는다 */
public enum InventoryStatusFilter {
    /** 수량이 남은 lot 중 24시간 안에 기한이 끝나는 lot이 있다 */
    IMMINENT,
    /** 수량이 남은 lot 중 기한이 지난 lot이 있다 */
    EXPIRED,
    /** 구매 가능 수량이 0이다 */
    SOLD_OUT,
    /** 구매 가능 수량이 안전재고(S-40)보다 적다 */
    LOW_STOCK
}
