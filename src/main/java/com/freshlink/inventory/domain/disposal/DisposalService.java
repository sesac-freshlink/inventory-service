package com.freshlink.inventory.domain.disposal;

import java.time.Clock;
import java.time.LocalDateTime;

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

        Disposal disposal = disposalRepository.save(Disposal.request(
                storeId,
                lot.getId(),
                quantity,
                userId,
                LocalDateTime.now(clock)
        ));

        outboxWriter.save(commandsQueue, DisposeMessage.of(
                disposal.getId(),
                storeId,
                lot.getId(),
                quantity,
                userId
        ));

        log.info(
                "폐기 요청: disposalId={}, storeId={}, lotId={}, quantity={}",
                disposal.getId(),
                storeId,
                lot.getId(),
                quantity
        );

        return new DisposalResponse(disposal.getId(), lot.getId(), quantity);
    }
}
