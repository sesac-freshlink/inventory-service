package com.freshlink.inventory.domain.storestock;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 조회 표에 쓰는 유일한 곳. {@link StockChanged}를 받아 그 행을 upsert 한다.
 * {@code @EventListener}는 보낸 쪽 스레드·트랜잭션에서 바로 돈다(커밋 뒤가 아니다)
 */
@Component
public class StoreStockProjector {

    private final StoreProductStockRepository repository;
    private final Clock clock;

    public StoreStockProjector(StoreProductStockRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @EventListener
    public void on(StockChanged event) {
        repository.upsert(
                event.storeId(),
                event.productId(),
                event.availableQuantity(),
                event.validUntil(),
                LocalDateTime.now(clock)
        );
    }
}
