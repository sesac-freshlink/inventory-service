package com.freshlink.inventory.domain.inventory;

/** S-42 집계 한 줄: 한 상품의 한 변동 종류 합계(부호 포함). {@link LedgerRepository#sumByProductAndType} 결과 */
public record ProductLedgerSum(
        Long productId,
        LedgerType type,
        Long quantity
) {
}
