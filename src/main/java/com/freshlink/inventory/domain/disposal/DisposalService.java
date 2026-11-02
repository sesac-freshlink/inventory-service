package com.freshlink.inventory.domain.disposal;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.freshlink.common.error.BusinessException;
import com.freshlink.common.message.command.DisposeMessage;
import com.freshlink.common.outbox.OutboxWriter;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.inventory.InventoryCalculationService;
import com.freshlink.inventory.domain.inventory.InventoryLot;
import com.freshlink.inventory.domain.inventory.InventoryLotRepository;
import com.freshlink.inventory.domain.inventory.InventoryLotStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-32 폐기 요청. 검사를 통과하면 disposal(REQUESTED)과 DISPOSE 쪽지를 한 트랜잭션으로 저장한다.
 * 재고는 여기서 바꾸지 않는다. 같은 점포 큐(commands.fifo)를 거쳐 쪽지를 처리할 때 바뀐다 (흐름 14).
 */
@Service
public class DisposalService {

    private static final Logger log = LoggerFactory.getLogger(DisposalService.class);

    private final InventoryLotRepository inventoryLotRepository;
    private final DisposalRepository disposalRepository;
    private final InventoryCalculationService calculationService;
    private final OutboxWriter outboxWriter;
    private final String commandsQueue;
    private final Clock clock;

    public DisposalService(
            InventoryLotRepository inventoryLotRepository,
            DisposalRepository disposalRepository,
            InventoryCalculationService calculationService,
            OutboxWriter outboxWriter,
            @Value("${app.queue.commands}") String commandsQueue,
            Clock clock
    ) {
        this.inventoryLotRepository = inventoryLotRepository;
        this.disposalRepository = disposalRepository;
        this.calculationService = calculationService;
        this.outboxWriter = outboxWriter;
        this.commandsQueue = commandsQueue;
        this.clock = clock;
    }

    @Transactional
    public DisposalResponse request(Long storeId, String userId, DisposalRequest request) {
        InventoryLot lot = inventoryLotRepository.findById(request.lotId())
                .orElseThrow(() -> new BusinessException(InventoryErrorCode.LOT_NOT_FOUND));

        if (!lot.getStoreId().equals(storeId)) {
            throw new BusinessException(InventoryErrorCode.LOT_NOT_IN_STORE);
        }

        if (calculationService.calculateStatus(lot) == InventoryLotStatus.NORMAL) {
            throw new BusinessException(InventoryErrorCode.NOT_DISPOSABLE);
        }

        int quantity = request.quantity();
        if (quantity > lot.availableQuantity()) {
            throw new BusinessException(InventoryErrorCode.INVALID_QUANTITY);
        }

        return save(storeId, userId, lot.getId(), quantity, LocalDateTime.now(clock));
    }

    /**
     * 경과 lot 한 번에 폐기. 그 점포에서 기한이 지난 lot마다 폐기 가능 수량(수량 − 예약) 전부를 lot 하나씩 요청한다.
     * 이미 요청해 두고 아직 처리 전(REQUESTED)인 수량은 빼서, 버튼을 두 번 눌러도 같은 수량을 또 요청하지 않는다.
     * 버릴 lot이 없으면 빈 목록이다(오류 아님). 재고는 단건 폐기와 똑같이 DISPOSE 쪽지를 처리할 때 줄어든다.
     */
    @Transactional
    public ExpiredDisposalResponse requestExpired(Long storeId, String userId) {
        LocalDateTime now = LocalDateTime.now(clock);

        Map<Long, Integer> pending = new HashMap<>();
        for (Disposal requested : disposalRepository.findByStoreIdAndStatus(storeId, DisposalStatus.REQUESTED)) {
            pending.merge(requested.getLotId(), requested.getQuantity(), Integer::sum);
        }

        List<DisposalResponse> items = inventoryLotRepository.findByStoreId(storeId).stream()
                .filter(lot -> calculationService.calculateStatus(lot) == InventoryLotStatus.EXPIRED)
                .sorted(Comparator.comparing(InventoryLot::getExpiryDate).thenComparing(InventoryLot::getId))
                .map(lot -> {
                    int quantity = lot.availableQuantity() - pending.getOrDefault(lot.getId(), 0);
                    return quantity > 0 ? save(storeId, userId, lot.getId(), quantity, now) : null;
                })
                .filter(Objects::nonNull)
                .toList();

        log.info("경과 lot 한 번에 폐기 요청: storeId={}, lots={}, quantity={}", storeId, items.size(),
                items.stream().mapToInt(DisposalResponse::quantity).sum());
        return new ExpiredDisposalResponse(items.size(), items.stream().mapToInt(DisposalResponse::quantity).sum(), items);
    }

    /** disposal(REQUESTED)과 DISPOSE 쪽지를 같은 트랜잭션에 저장한다 */
    private DisposalResponse save(Long storeId, String userId, Long lotId, int quantity, LocalDateTime now) {
        Disposal disposal = disposalRepository.save(Disposal.request(storeId, lotId, quantity, userId, now));

        outboxWriter.save(commandsQueue, DisposeMessage.of(
                disposal.getId(),
                storeId,
                lotId,
                quantity,
                userId
        ));

        log.info(
                "폐기 요청: disposalId={}, storeId={}, lotId={}, quantity={}",
                disposal.getId(),
                storeId,
                lotId,
                quantity
        );

        return new DisposalResponse(disposal.getId(), lotId, quantity);
    }
}
