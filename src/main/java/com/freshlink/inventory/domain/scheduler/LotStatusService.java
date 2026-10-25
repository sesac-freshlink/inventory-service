package com.freshlink.inventory.domain.scheduler;

import java.time.Clock;
import java.time.LocalDateTime;

import com.freshlink.inventory.domain.inventory.InventoryLotRepository;
import com.freshlink.inventory.domain.inventory.InventoryLotStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * lot.status(표시용)를 지금 시각 기준으로 갱신한다 (명세 6, 2-3).
 * 판매 차단(S-31)은 이 값에 기대지 않고 항상 expiry_date > now로 거른다. 자동 폐기는 하지 않는다.
 * 기한은 뒤로 가지 않으므로 NORMAL로 되돌리는 UPDATE는 없다.
 */
@Service
public class LotStatusService {

    private static final Logger log = LoggerFactory.getLogger(LotStatusService.class);

    private final InventoryLotRepository inventoryLotRepository;
    private final Clock clock;

    public LotStatusService(
            InventoryLotRepository inventoryLotRepository,
            Clock clock
    ) {
        this.inventoryLotRepository = inventoryLotRepository;
        this.clock = clock;
    }

    @Transactional
    public LotStatusRefreshResult refresh() {
        LocalDateTime now = LocalDateTime.now(clock);

        int expired = inventoryLotRepository.markExpired(
                now,
                InventoryLotStatus.EXPIRED
        );
        int imminent = inventoryLotRepository.markImminent(
                now,
                now.plusHours(24),
                InventoryLotStatus.IMMINENT
        );

        log.info("유통기한 상태 갱신: 경과 {}건, 임박 {}건", expired, imminent);
        return new LotStatusRefreshResult(expired, imminent);
    }

    public record LotStatusRefreshResult(int expired, int imminent) {
    }
}
