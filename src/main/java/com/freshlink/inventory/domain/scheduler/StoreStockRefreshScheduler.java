package com.freshlink.inventory.domain.scheduler;

import com.freshlink.inventory.domain.storestock.StoreStockRefresher;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 고객용 재고 조회 표 정리. 1분마다 값이 맞는 시각이 지난 행을 다시 계산한다.
 * 파드 2개가 같이 깨어나도 ShedLock(storeStockRefreshJob)으로 한 파드만 돈다
 */
@Component
public class StoreStockRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(StoreStockRefreshScheduler.class);

    private final StoreStockRefresher refresher;

    public StoreStockRefreshScheduler(StoreStockRefresher refresher) {
        this.refresher = refresher;
    }

    @Scheduled(
            fixedDelayString = "${app.store-stock.refresh-delay-ms}",
            initialDelayString = "${app.store-stock.refresh-delay-ms}"
    )
    @SchedulerLock(name = "storeStockRefreshJob", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    public void refresh() {
        int refreshed = refresher.refreshStale();
        if (refreshed > 0) {
            log.info("조회 표 정리: 값이 맞는 시각이 지난 {}행을 다시 계산", refreshed);
        }
    }
}
