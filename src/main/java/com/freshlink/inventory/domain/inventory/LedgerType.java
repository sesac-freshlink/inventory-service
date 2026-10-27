package com.freshlink.inventory.domain.inventory;

public enum LedgerType {
    INBOUND,
    SALE,
    DISPOSE,
    /** 점주 수동 조정·실사(S-14). 수량은 증감 그대로(±) */
    ADJUST
}