package com.freshlink.inventory.domain.inventory;

public enum LedgerType {
    INBOUND,
    SALE,
    DISPOSE,
    /** 점주 수동 조정·실사(S-14). 수량은 증감 그대로(±) */
    ADJUST,
    /** 환불 뒤 판매 재고 복원(A10, RETURN 쪽지). 판매(SALE)를 되돌리므로 수량은 양수(+). ref_id는 주문 ID */
    RETURN
}