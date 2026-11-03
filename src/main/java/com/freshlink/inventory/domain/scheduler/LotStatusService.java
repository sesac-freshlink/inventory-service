package com.freshlink.inventory.domain.scheduler;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import com.freshlink.common.message.notification.NotificationMessage;
import com.freshlink.common.outbox.OutboxWriter;
import com.freshlink.inventory.domain.inventory.InventoryLotRepository;
import com.freshlink.inventory.domain.inventory.InventoryLotStatus;
import com.freshlink.inventory.domain.inventory.LotTransitionRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * lot.status(표시용)를 지금 시각 기준으로 갱신한다 (명세 6, 2-3).
 * 판매 차단(S-31)은 이 값에 기대지 않고 항상 expiry_date > now로 거른다. 자동 폐기는 하지 않는다.
 * 기한은 뒤로 가지 않으므로 NORMAL로 되돌리는 UPDATE는 없다.
 *
 * <p>상태가 IMMINENT·EXPIRED로 <b>바뀌는 lot마다</b> 점주 화면 알림 쪽지(common 0.6.0 NotificationMessage)를
 * 같은 트랜잭션의 Outbox로 남긴다. 바뀔 lot을 먼저 읽고 그 lot만 바꾸므로, 스케줄러가 몇 번 돌아도
 * 같은 lot은 상태마다 한 번만 알림이 나간다. 남은 수량이 0인 lot은 바꾸지도 보내지도 않는다.
 */
@Service
public class LotStatusService {

    private static final Logger log = LoggerFactory.getLogger(LotStatusService.class);

    private final InventoryLotRepository inventoryLotRepository;
    private final OutboxWriter outboxWriter;
    private final String notificationQueue;
    private final Clock clock;

    public LotStatusService(
            InventoryLotRepository inventoryLotRepository,
            OutboxWriter outboxWriter,
            @Value("${app.queue.notifications}") String notificationQueue,
            Clock clock
    ) {
        this.inventoryLotRepository = inventoryLotRepository;
        this.outboxWriter = outboxWriter;
        this.notificationQueue = notificationQueue;
        this.clock = clock;
    }

    @Transactional
    public LotStatusRefreshResult refresh() {
        LocalDateTime now = LocalDateTime.now(clock);

        // 경과를 먼저 본다. 임박이던 lot이 기한을 넘기면 EXPIRED 알림 하나만 간다
        List<LotTransitionRow> expired = inventoryLotRepository.findBecomingExpired(now);
        change(expired, InventoryLotStatus.EXPIRED, now);
        expired.stream().filter(this::hasProduct).forEach(lot -> outboxWriter.save(notificationQueue,
                NotificationMessage.expired(lot.storeId(), lot.productId(), lot.productName(), lot.lotId(),
                        expiresAt(lot), lot.quantity())));

        List<LotTransitionRow> imminent = inventoryLotRepository.findBecomingImminent(now, now.plusHours(24));
        change(imminent, InventoryLotStatus.IMMINENT, now);
        imminent.stream().filter(this::hasProduct).forEach(lot -> outboxWriter.save(notificationQueue,
                NotificationMessage.expiryImminent(lot.storeId(), lot.productId(), lot.productName(), lot.lotId(),
                        expiresAt(lot), lot.quantity())));

        log.info("유통기한 상태 갱신: 경과 {}건, 임박 {}건", expired.size(), imminent.size());
        return new LotStatusRefreshResult(expired.size(), imminent.size());
    }

    /** 알림 문구에 상품명이 필요하다. 상품 표에 없는 lot(정상이면 생기지 않는다)은 상태만 바꾸고 알림은 건너뛴다 */
    private boolean hasProduct(LotTransitionRow lot) {
        if (lot.productName() == null) {
            log.warn("유통기한 알림 건너뜀: 상품이 없는 lot: lotId={}, productId={}", lot.lotId(), lot.productId());
            return false;
        }
        return true;
    }

    private void change(List<LotTransitionRow> lots, InventoryLotStatus status, LocalDateTime now) {
        if (!lots.isEmpty()) {
            inventoryLotRepository.markStatus(lots.stream().map(LotTransitionRow::lotId).toList(), status, now);
        }
    }

    private java.time.OffsetDateTime expiresAt(LotTransitionRow lot) {
        return lot.expiryDate().atZone(clock.getZone()).toOffsetDateTime();
    }

    public record LotStatusRefreshResult(int expired, int imminent) {
    }
}
