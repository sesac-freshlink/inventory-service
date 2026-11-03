package com.freshlink.inventory.domain.storestock;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 유통기한은 쪽지 없이 시간이 지나면 바뀐다. 값이 맞는 시각이 지난 행을 lot에서 다시 계산한다.
 * 그 사이 고객 목록은 그 행을 품절로 본다({@link StoreProductStock#availableAt}).
 * 행마다 따로 트랜잭션이라(publish가 @Transactional) 한 행이 실패해도 나머지는 고친다
 */
@Component
public class StoreStockRefresher {

    private final StoreProductStockRepository repository;
    private final StockChangePublisher stockChanges;
    private final Clock clock;

    public StoreStockRefresher(
            StoreProductStockRepository repository,
            StockChangePublisher stockChanges,
            Clock clock
    ) {
        this.repository = repository;
        this.stockChanges = stockChanges;
        this.clock = clock;
    }

    /** 다시 계산한 행 수를 돌려준다 */
    public int refreshStale() {
        List<StoreProductStock> stale = repository.findByValidUntilLessThanEqual(LocalDateTime.now(clock));
        stale.forEach(row -> stockChanges.publish(row.getStoreId(), List.of(row.getProductId())));
        return stale.size();
    }
}
