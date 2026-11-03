package com.freshlink.inventory.domain.storestock;

import java.time.LocalDateTime;

/**
 * 재고가 바뀐 뒤의 점포·상품별 구매 가능 수량. 변화량이 아니라 결과값을 담아서 두 번 받아도 결과가 같다.
 * 지금은 같은 트랜잭션 안의 스프링 이벤트로만 쓴다. 조회 표를 별도 서비스로 나누면 이 모양 그대로 outbox → 큐로 보낸다
 * (그때는 순서가 섞일 수 있으므로 버전을 더한다. docs/고객용-재고-조회-표-CQRS.md)
 */
public record StockChanged(
        Long storeId,
        Long productId,
        int availableQuantity,
        LocalDateTime validUntil
) {
}
