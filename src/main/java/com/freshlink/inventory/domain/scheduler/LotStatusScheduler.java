package com.freshlink.inventory.domain.scheduler;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 유통기한 스케줄러(E-09). 기본은 자정, 시연 때는 EXPIRY_CRON=0 * * * * *(1분).
 * 파드 2개가 같은 시각에 깨어나도 ShedLock(lotStatusJob)으로 한 파드만 돈다.
 * lockAtLeastFor 10초는 두 파드의 시계가 조금 어긋나도 같은 회차를 두 번 돌지 않게 한다.
 */
@Component
public class LotStatusScheduler {

    private final LotStatusService lotStatusService;

    public LotStatusScheduler(LotStatusService lotStatusService) {
        this.lotStatusService = lotStatusService;
    }

    @Scheduled(cron = "${app.expiry.cron}", zone = "Asia/Seoul")
    @SchedulerLock(name = "lotStatusJob", lockAtLeastFor = "PT10S", lockAtMostFor = "PT5M")
    public void refreshLotStatus() {
        lotStatusService.refresh();
    }
}
