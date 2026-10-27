package com.freshlink.inventory.domain.adjustment;

/** 수동 조정 사유(S-14) */
public enum AdjustmentReason {
    /** 실사: 직접 센 수량과 맞춘다 */
    COUNT,
    /** 파손 */
    DAMAGE,
    /** 분실 */
    LOSS,
    /** 기타. 메모에 적는다 */
    OTHER
}
