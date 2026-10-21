package com.freshlink.inventory.domain.inventory;

import java.time.Clock;
import java.time.LocalDateTime;

import com.freshlink.common.error.BusinessException;
import com.freshlink.common.idempotency.ProcessedMessages;
import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.inbound.Inbound;
import com.freshlink.inventory.domain.inbound.InboundRepository;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryCommandService {

    private static final Logger log =
            LoggerFactory.getLogger(InventoryCommandService.class);
    private final ProcessedMessages processedMessages;
    private final InboundRepository inboundRepository;
    private final ProductRepository productRepository;
    private final InventoryLotRepository inventoryLotRepository;
    private final LedgerRepository ledgerRepository;
    private final Clock clock;

    public InventoryCommandService(
            ProcessedMessages processedMessages,
            InboundRepository inboundRepository,
            ProductRepository productRepository,
            InventoryLotRepository inventoryLotRepository,
            LedgerRepository ledgerRepository,
            Clock clock
    ) {
        this.processedMessages = processedMessages;
        this.inboundRepository = inboundRepository;
        this.productRepository = productRepository;
        this.inventoryLotRepository = inventoryLotRepository;
        this.ledgerRepository = ledgerRepository;
        this.clock = clock;
    }

    @Transactional
    public void handle(BaseMessage message) {
        if (!processedMessages.markIfNew(message.messageId())) {
            return;
        }

        if (message instanceof InboundMessage inboundMessage) {
            handleInbound(inboundMessage);
            return;
        }

        throw new IllegalArgumentException(
                "지원하지 않는 inventory command type: " + message.type()
        );
    }

    private void handleInbound(InboundMessage message) {
        if (inboundRepository.existsByShipmentId(message.shipmentId())) {
            return;
        }

        for (InboundItem item : message.items()) {
            processInboundItem(message, item);
        }

        inboundRepository.save(Inbound.create(
                message.shipmentId(),
                message.storeId(),
                LocalDateTime.now(clock)
        ));
    }

    private void processInboundItem(
            InboundMessage message,
            InboundItem item
    ) {
        if (item.quantity() <= 0) {
            log.warn(
                    "INBOUND 수량 검증 실패: shipmentId={}, productCode={}, quantity={}",
                    message.shipmentId(),
                    item.productCode(),
                    item.quantity()
            );
            throw new BusinessException(InventoryErrorCode.INVALID_QUANTITY);
        }

        Product product = productRepository.findByProductCode(item.productCode())
                .orElseThrow(() -> {
                    log.warn(
                            "INBOUND 상품 조회 실패: shipmentId={}, productCode={}",
                            message.shipmentId(),
                            item.productCode()
                    );
                    return new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND);
                });

        LocalDateTime expiryDate = item.expiryDate()
                .atZoneSameInstant(clock.getZone())
                .toLocalDateTime();

        InventoryLot lot = inventoryLotRepository
                .findByStoreIdAndProductIdAndExpiryDate(
                        message.storeId(),
                        product.getId(),
                        expiryDate
                )
                .map(existingLot -> {
                    existingLot.addQuantity(item.quantity());
                    return existingLot;
                })
                .orElseGet(() -> InventoryLot.create(
                        message.storeId(),
                        product.getId(),
                        expiryDate,
                        item.quantity()
                ));

        InventoryLot savedLot = inventoryLotRepository.save(lot);

        ledgerRepository.save(Ledger.inbound(
                message.storeId(),
                savedLot.getId(),
                item.quantity(),
                message.shipmentId()
        ));
    }
}
