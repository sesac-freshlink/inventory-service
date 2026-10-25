package com.freshlink.inventory.domain.inventory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.freshlink.common.error.BusinessException;
import com.freshlink.common.idempotency.ProcessedMessages;
import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.common.message.result.ReserveFailReason;
import com.freshlink.common.message.result.ReserveFailedMessage;
import com.freshlink.common.message.result.ReservedLot;
import com.freshlink.common.message.result.ReservedMessage;
import com.freshlink.common.outbox.OutboxWriter;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.inbound.Inbound;
import com.freshlink.inventory.domain.inbound.InboundRepository;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final ReservationRepository reservationRepository;
    private final OutboxWriter outboxWriter;
    private final String resultsQueue;
    private final Clock clock;

    public InventoryCommandService(
            ProcessedMessages processedMessages,
            InboundRepository inboundRepository,
            ProductRepository productRepository,
            InventoryLotRepository inventoryLotRepository,
            LedgerRepository ledgerRepository,
            ReservationRepository reservationRepository,
            OutboxWriter outboxWriter,
            @Value("${app.queue.results}") String resultsQueue,
            Clock clock
    ) {
        this.processedMessages = processedMessages;
        this.inboundRepository = inboundRepository;
        this.productRepository = productRepository;
        this.inventoryLotRepository = inventoryLotRepository;
        this.ledgerRepository = ledgerRepository;
        this.reservationRepository = reservationRepository;
        this.outboxWriter = outboxWriter;
        this.resultsQueue = resultsQueue;
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

        if (message instanceof ReserveMessage reserveMessage) {
            handleReserve(reserveMessage);
            return;
        }

        if (message instanceof ConfirmMessage confirmMessage) {
            handleConfirm(confirmMessage);
            return;
        }

        if (message instanceof ReleaseMessage releaseMessage) {
            handleRelease(releaseMessage);
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

    private void handleReserve(ReserveMessage message) {
        List<Reservation> existing =
                reservationRepository.findByOrderIdOrderByIdAsc(message.orderId());
        if (!existing.isEmpty()) {
            resendReserved(message, existing);
            return;
        }

        if (message.quantity() < 1) {
            log.warn(
                    "RESERVE 수량 검증 실패: orderId={}, productId={}, quantity={}",
                    message.orderId(),
                    message.productId(),
                    message.quantity()
            );
            saveReserveFailed(message, ReserveFailReason.INVALID_QUANTITY, 0);
            return;
        }

        Product product = productRepository.findById(message.productId()).orElse(null);
        if (product == null) {
            log.warn(
                    "RESERVE 상품 조회 실패: orderId={}, productId={}",
                    message.orderId(),
                    message.productId()
            );
            saveReserveFailed(message, ReserveFailReason.PRODUCT_NOT_FOUND, 0);
            return;
        }

        // FEFO: 기한이 지나지 않은 lot만 유통기한 빠른 순으로 읽는다 (S-31)
        List<InventoryLot> lots = inventoryLotRepository
                .findByStoreIdAndProductIdAndExpiryDateAfterOrderByExpiryDateAscIdAsc(
                        message.storeId(),
                        message.productId(),
                        LocalDateTime.now(clock)
                );

        int availableQuantity = lots.stream()
                .mapToInt(InventoryLot::availableQuantity)
                .sum();
        if (availableQuantity < message.quantity()) {
            log.info(
                    "RESERVE 재고 부족: orderId={}, productId={}, quantity={}, available={}",
                    message.orderId(),
                    message.productId(),
                    message.quantity(),
                    availableQuantity
            );
            saveReserveFailed(message, ReserveFailReason.OUT_OF_STOCK, availableQuantity);
            return;
        }

        int remaining = message.quantity();
        List<ReservedLot> reservedLots = new ArrayList<>();
        for (InventoryLot lot : lots) {
            if (remaining == 0) {
                break;
            }
            int quantity = Math.min(remaining, lot.availableQuantity());
            if (quantity == 0) {
                continue;
            }
            lot.reserve(quantity);
            reservationRepository.save(Reservation.reserve(
                    message.orderId(),
                    lot.getId(),
                    quantity
            ));
            reservedLots.add(new ReservedLot(
                    lot.getId(),
                    quantity,
                    toOffsetDateTime(lot.getExpiryDate())
            ));
            remaining -= quantity;
        }

        outboxWriter.save(resultsQueue, ReservedMessage.of(
                message.orderId(),
                message.storeId(),
                message.productId(),
                product.getName(),
                message.quantity(),
                product.getPrice(),
                reservedLots
        ));

        log.info(
                "RESERVE 처리: orderId={}, productId={}, quantity={}, lots={}",
                message.orderId(),
                message.productId(),
                message.quantity(),
                reservedLots.size()
        );
    }

    /** 같은 orderId가 다른 messageId로 다시 오면 이미 잡은 예약으로 같은 RESERVED를 다시 보낸다 (명세 5-3 1번) */
    private void resendReserved(ReserveMessage message, List<Reservation> reservations) {
        boolean allReserved = reservations.stream()
                .allMatch(Reservation::isReserved);
        if (!allReserved) {
            log.warn(
                    "RESERVE 재수신: 이미 확정·해제된 주문이라 결과를 보내지 않는다: orderId={}, messageId={}",
                    message.orderId(),
                    message.messageId()
            );
            return;
        }

        Map<Long, InventoryLot> lotsById = inventoryLotRepository
                .findAllById(reservations.stream().map(Reservation::getLotId).toList())
                .stream()
                .collect(Collectors.toMap(InventoryLot::getId, Function.identity()));

        // 점포·상품은 받은 쪽지가 아니라 이미 잡은 lot에서 읽는다. 잘못된 재수신이 와도 처음과 같은 결과를 보낸다
        InventoryLot reservedLot = lotsById.get(reservations.getFirst().getLotId());
        Product product = productRepository.findById(reservedLot.getProductId())
                .orElseThrow(() -> new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND));

        List<ReservedLot> reservedLots = reservations.stream()
                .map(reservation -> new ReservedLot(
                        reservation.getLotId(),
                        reservation.getQuantity(),
                        toOffsetDateTime(lotsById.get(reservation.getLotId()).getExpiryDate())
                ))
                .toList();

        int quantity = reservations.stream()
                .mapToInt(Reservation::getQuantity)
                .sum();

        outboxWriter.save(resultsQueue, ReservedMessage.of(
                message.orderId(),
                reservedLot.getStoreId(),
                reservedLot.getProductId(),
                product.getName(),
                quantity,
                product.getPrice(),
                reservedLots
        ));

        log.info(
                "RESERVE 재수신: 같은 RESERVED를 다시 보낸다: orderId={}, messageId={}",
                message.orderId(),
                message.messageId()
        );
    }

    /**
     * 결제 성공. RESERVED 예약마다 실물·예약을 줄이고 원장 SALE을 남긴다. 결과 쪽지는 없다 (명세 5-4).
     * 예약이 없거나 해제된 주문은 고칠 방법이 없어 에러 로그만 남기고 쪽지는 정상 처리로 끝낸다.
     */
    private void handleConfirm(ConfirmMessage message) {
        List<Reservation> reservations =
                reservationRepository.findByOrderIdOrderByIdAsc(message.orderId());

        boolean allReleased = reservations.stream()
                .allMatch(reservation -> reservation.getStatus() == ReservationStatus.RELEASED);
        if (reservations.isEmpty() || allReleased) {
            log.error(
                    "CONFIRM 반영 안 함: 예약이 없거나 해제된 주문: orderId={}, storeId={}, messageId={}",
                    message.orderId(),
                    message.storeId(),
                    message.messageId()
            );
            return;
        }

        List<Reservation> reserved = reservations.stream()
                .filter(Reservation::isReserved)
                .toList();
        if (reserved.isEmpty()) {
            log.info(
                    "CONFIRM 이미 확정된 주문: orderId={}, messageId={}",
                    message.orderId(),
                    message.messageId()
            );
            return;
        }

        Map<Long, InventoryLot> lots = lotsOfStore(message.storeId(), message.orderId(), reserved);
        for (Reservation reservation : reserved) {
            InventoryLot lot = lots.get(reservation.getLotId());
            lot.confirm(reservation.getQuantity());
            reservation.confirm();
            ledgerRepository.save(Ledger.sale(
                    lot.getStoreId(),
                    lot.getId(),
                    reservation.getQuantity(),
                    String.valueOf(message.orderId())
            ));
        }

        log.info(
                "CONFIRM 처리: orderId={}, lots={}",
                message.orderId(),
                reserved.size()
        );
    }

    /** 결제 실패·미결제 만료. RESERVED 예약을 되돌린다. 원장은 남기지 않는다 (명세 5-5) */
    private void handleRelease(ReleaseMessage message) {
        List<Reservation> reserved = reservationRepository
                .findByOrderIdOrderByIdAsc(message.orderId())
                .stream()
                .filter(Reservation::isReserved)
                .toList();
        if (reserved.isEmpty()) {
            log.info(
                    "RELEASE 해제할 예약 없음: orderId={}, reason={}, messageId={}",
                    message.orderId(),
                    message.reason(),
                    message.messageId()
            );
            return;
        }

        Map<Long, InventoryLot> lots = lotsOfStore(message.storeId(), message.orderId(), reserved);
        for (Reservation reservation : reserved) {
            lots.get(reservation.getLotId()).release(reservation.getQuantity());
            reservation.release();
        }

        log.info(
                "RELEASE 처리: orderId={}, reason={}, lots={}",
                message.orderId(),
                message.reason(),
                reserved.size()
        );
    }

    /**
     * 확정·해제 전에 예약한 lot이 모두 쪽지의 점포 것인지 확인한다. 하나라도 다르면 아무것도 바꾸지 않고 예외(DLQ)로 끝낸다.
     * 다른 점포 lot을 바꾸면 점포별 FIFO 줄 밖에서 재고가 바뀌어 순서 보장이 깨진다
     */
    private Map<Long, InventoryLot> lotsOfStore(Long storeId, Long orderId, List<Reservation> reservations) {
        Map<Long, InventoryLot> lots = new HashMap<>();
        for (Reservation reservation : reservations) {
            InventoryLot lot = findLot(reservation);
            if (!lot.getStoreId().equals(storeId)) {
                throw new IllegalStateException(
                        "다른 점포의 예약이다: orderId=" + orderId
                                + ", storeId=" + storeId
                                + ", lotId=" + lot.getId()
                                + ", lotStoreId=" + lot.getStoreId()
                );
            }
            lots.put(lot.getId(), lot);
        }
        return lots;
    }

    private InventoryLot findLot(Reservation reservation) {
        return inventoryLotRepository.findById(reservation.getLotId())
                .orElseThrow(() -> new IllegalStateException(
                        "예약의 lot이 없다: reservationId=" + reservation.getId()
                                + ", lotId=" + reservation.getLotId()
                ));
    }

    private void saveReserveFailed(
            ReserveMessage message,
            ReserveFailReason reason,
            int availableQuantity
    ) {
        outboxWriter.save(resultsQueue, ReserveFailedMessage.of(
                message.orderId(),
                message.storeId(),
                message.productId(),
                reason,
                availableQuantity
        ));
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        return dateTime.atZone(clock.getZone()).toOffsetDateTime();
    }
}
