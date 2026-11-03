package com.freshlink.inventory.domain.inventory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.freshlink.common.error.BusinessException;
import com.freshlink.common.idempotency.ProcessedMessages;
import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.DisposeMessage;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.common.message.command.ReturnMessage;
import com.freshlink.common.message.notification.NotificationMessage;
import com.freshlink.common.message.result.ReserveFailReason;
import com.freshlink.common.message.result.ReserveFailedMessage;
import com.freshlink.common.message.result.ReservedLot;
import com.freshlink.common.message.result.ReservedMessage;
import com.freshlink.common.outbox.OutboxWriter;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.disposal.Disposal;
import com.freshlink.inventory.domain.disposal.DisposalRepository;
import com.freshlink.inventory.domain.firstcome.FirstComeService;
import com.freshlink.inventory.domain.inbound.Inbound;
import com.freshlink.inventory.domain.inbound.InboundRepository;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;
import com.freshlink.inventory.domain.storestock.StockChangePublisher;

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
    private final DisposalRepository disposalRepository;
    private final OutboxWriter outboxWriter;
    private final StockChangePublisher stockChanges;
    private final FirstComeService firstComeService;
    private final String resultsQueue;
    private final String notificationQueue;
    private final Clock clock;

    public InventoryCommandService(
            ProcessedMessages processedMessages,
            InboundRepository inboundRepository,
            ProductRepository productRepository,
            InventoryLotRepository inventoryLotRepository,
            LedgerRepository ledgerRepository,
            ReservationRepository reservationRepository,
            DisposalRepository disposalRepository,
            OutboxWriter outboxWriter,
            StockChangePublisher stockChanges,
            FirstComeService firstComeService,
            @Value("${app.queue.results}") String resultsQueue,
            @Value("${app.queue.notifications}") String notificationQueue,
            Clock clock
    ) {
        this.processedMessages = processedMessages;
        this.inboundRepository = inboundRepository;
        this.productRepository = productRepository;
        this.inventoryLotRepository = inventoryLotRepository;
        this.ledgerRepository = ledgerRepository;
        this.reservationRepository = reservationRepository;
        this.disposalRepository = disposalRepository;
        this.outboxWriter = outboxWriter;
        this.stockChanges = stockChanges;
        this.firstComeService = firstComeService;
        this.resultsQueue = resultsQueue;
        this.notificationQueue = notificationQueue;
        this.clock = clock;
    }

    /** 같은 messageId를 이미 처리했으면 아무것도 바꾸지 않고 DUPLICATE */
    @Transactional
    public CommandResult handle(BaseMessage message) {
        if (!processedMessages.markIfNew(message.messageId())) {
            return CommandResult.DUPLICATE;
        }

        if (message instanceof InboundMessage inboundMessage) {
            handleInbound(inboundMessage);
        } else if (message instanceof ReserveMessage reserveMessage) {
            handleReserve(reserveMessage);
        } else if (message instanceof ConfirmMessage confirmMessage) {
            handleConfirm(confirmMessage);
        } else if (message instanceof ReleaseMessage releaseMessage) {
            handleRelease(releaseMessage);
        } else if (message instanceof DisposeMessage disposeMessage) {
            handleDispose(disposeMessage);
        } else if (message instanceof ReturnMessage returnMessage) {
            handleReturn(returnMessage);
        } else {
            throw new IllegalArgumentException(
                    "지원하지 않는 inventory command type: " + message.type()
            );
        }

        return CommandResult.PROCESSED;
    }

    private void handleInbound(InboundMessage message) {
        if (inboundRepository.existsByShipmentId(message.shipmentId())) {
            return;
        }

        // 재입고 알림: 입고 전에 팔 수 있는 수량이 0이던 상품만. 같은 쪽지에 같은 상품이 여러 줄이어도 처음 값으로 본다
        Map<Long, Integer> sellableBefore = new HashMap<>();
        for (InboundItem item : message.items()) {
            productRepository.findByProductCode(item.productCode())
                    .ifPresent(product -> sellableBefore.computeIfAbsent(
                            product.getId(), id -> sellable(message.storeId(), id)));
        }

        Set<Long> productIds = new TreeSet<>();
        for (InboundItem item : message.items()) {
            productIds.add(processInboundItem(message, item));
        }

        inboundRepository.save(Inbound.create(
                message.shipmentId(),
                message.storeId(),
                LocalDateTime.now(clock)
        ));
        stockChanges.publish(message.storeId(), productIds);
        saveRestocked(message.storeId(), productIds, sellableBefore);
    }

    /** lot에 더하고 원장을 남긴다. 고객용 조회 표를 다시 계산하도록 상품 ID를 돌려준다 */
    private Long processInboundItem(
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
        return product.getId();
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
        // 선착순 상품이면 한도에서 남은 만큼만 잡을 수 있다. 넘으면 재고 부족과 같은 결과(OUT_OF_STOCK)로 거절한다
        Integer firstComeLeft = firstComeService.limitLeft(message.storeId(), message.productId());
        if (firstComeLeft != null && firstComeLeft < availableQuantity) {
            log.info(
                    "RESERVE 선착순 한도 적용: orderId={}, productId={}, available={}, limitLeft={}",
                    message.orderId(),
                    message.productId(),
                    availableQuantity,
                    firstComeLeft
            );
            availableQuantity = firstComeLeft;
        }
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
        stockChanges.publish(message.storeId(), List.of(message.productId()));

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
        stockChanges.publish(message.storeId(), productIdsOf(lots));

        log.info(
                "CONFIRM 처리: orderId={}, lots={}",
                message.orderId(),
                reserved.size()
        );
    }

    /** 결제 실패·미결제 만료·결제 전 고객 취소(CANCELED, v0.5.0). RESERVED 예약을 되돌린다. 원장은 남기지 않는다 (명세 5-5) */
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
        stockChanges.publish(message.storeId(), productIdsOf(lots));

        log.info(
                "RELEASE 처리: orderId={}, reason={}, lots={}",
                message.orderId(),
                message.reason(),
                reserved.size()
        );
    }

    /**
     * 환불 뒤 판매 재고 복원 (A10, v0.5.0). 쪽지에는 주문 ID·점포만 있어서, 그 주문의 판매 기록(SALE, ref_id = 주문 ID)으로
     * 원래 lot과 수량을 찾아 실물을 되돌리고 원장에 RETURN(+n)을 남긴다.
     * <ul>
     *   <li>주문당 한 번만 복원한다. messageId가 달라도 같은 주문의 RETURN 원장이 이미 있으면 아무것도 안 한다.</li>
     *   <li>판매 기록이 없으면(CONFIRM 전에 왔거나 잘못된 주문) 예외로 끝내 재시도·DLQ로 보낸다. 같은 점포 쪽지는 한 줄이라
     *       order가 CONFIRM 뒤에 보내면 이 경우는 생기지 않는다.</li>
     *   <li>유통기한이 지난 lot도 원래 lot에 그대로 되돌린다. 경과 lot은 이미 판매·예약에서 빠지므로 점주가 폐기로 정리한다.</li>
     * </ul>
     */
    private void handleReturn(ReturnMessage message) {
        String orderId = String.valueOf(message.orderId());
        if (ledgerRepository.existsByStoreIdAndTypeAndRefId(message.storeId(), LedgerType.RETURN, orderId)) {
            log.info(
                    "RETURN 이미 복원한 주문: orderId={}, storeId={}, messageId={}",
                    message.orderId(),
                    message.storeId(),
                    message.messageId()
            );
            return;
        }

        List<Ledger> sales = ledgerRepository.findByStoreIdAndTypeAndRefIdOrderByIdAsc(
                message.storeId(), LedgerType.SALE, orderId);
        if (sales.isEmpty()) {
            throw new IllegalStateException(
                    "RETURN 되돌릴 판매 기록이 없다: orderId=" + message.orderId()
                            + ", storeId=" + message.storeId()
            );
        }

        Map<Long, InventoryLot> lots = new HashMap<>();
        for (Ledger sale : sales) {
            InventoryLot lot = inventoryLotRepository.findById(sale.getLotId())
                    .orElseThrow(() -> new IllegalStateException(
                            "판매 기록의 lot이 없다: ledgerId=" + sale.getId() + ", lotId=" + sale.getLotId()));
            int quantity = -sale.getQuantity();
            lot.addQuantity(quantity);
            ledgerRepository.save(Ledger.returned(lot.getStoreId(), lot.getId(), quantity, orderId));
            lots.put(lot.getId(), lot);
        }
        stockChanges.publish(message.storeId(), productIdsOf(lots));

        log.info(
                "RETURN 처리: orderId={}, lots={}, quantity={}",
                message.orderId(),
                lots.size(),
                sales.stream().mapToInt(sale -> -sale.getQuantity()).sum()
        );
    }

    /**
     * 폐기 반영 (명세 5-6). 요청 뒤 처리 전에 예약이 들어왔을 수 있으므로 폐기 가능 수량을 다시 확인한다.
     * 모자라면 재고를 바꾸지 않고 SKIPPED로 끝낸다. 이미 처리된 폐기면 아무것도 안 한다.
     */
    private void handleDispose(DisposeMessage message) {
        Disposal disposal = disposalRepository.findById(message.disposalId())
                .orElseThrow(() -> new BusinessException(InventoryErrorCode.DISPOSAL_NOT_FOUND));

        if (!disposal.isRequested()) {
            log.info(
                    "DISPOSE 이미 처리된 폐기: disposalId={}, status={}",
                    disposal.getId(),
                    disposal.getStatus()
            );
            return;
        }

        InventoryLot lot = inventoryLotRepository.findById(disposal.getLotId())
                .orElseThrow(() -> new BusinessException(InventoryErrorCode.LOT_NOT_FOUND));
        int quantity = disposal.getQuantity();
        LocalDateTime now = LocalDateTime.now(clock);

        if (lot.availableQuantity() < quantity) {
            log.warn(
                    "DISPOSE 수량 부족으로 건너뜀: disposalId={}, lotId={}, quantity={}, available={}",
                    disposal.getId(),
                    lot.getId(),
                    quantity,
                    lot.availableQuantity()
            );
            disposal.skip(now);
            return;
        }

        lot.dispose(quantity);
        ledgerRepository.save(Ledger.dispose(
                lot.getStoreId(),
                lot.getId(),
                quantity,
                String.valueOf(disposal.getId())
        ));
        disposal.done(now);
        stockChanges.publish(lot.getStoreId(), List.of(lot.getProductId()));

        log.info(
                "DISPOSE 처리: disposalId={}, lotId={}, quantity={}",
                disposal.getId(),
                lot.getId(),
                quantity
        );
    }

    /**
     * 입고로 팔 수 있는 수량이 0 → 1 이상이 된 상품마다 재입고 알림 쪽지(RESTOCKED)를 같은 트랜잭션의 Outbox로 남긴다.
     * 이미 남아 있던 상품에 더해진 입고는 보내지 않는다. 신청한 고객이 있는지는 notification-service가 본다.
     * RETURN·RELEASE로 다시 생긴 수량은 재입고가 아니라서 여기(INBOUND)에서만 보낸다
     */
    private void saveRestocked(Long storeId, Set<Long> productIds, Map<Long, Integer> sellableBefore) {
        for (Long productId : productIds) {
            if (sellableBefore.getOrDefault(productId, 0) > 0) {
                continue;
            }
            int after = sellable(storeId, productId);
            if (after < 1) {
                continue;
            }
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND));
            outboxWriter.save(notificationQueue,
                    NotificationMessage.restocked(storeId, productId, product.getName(), after));
            log.info("재입고 알림 쪽지: storeId={}, productId={}, sellable={}", storeId, productId, after);
        }
    }

    /** 지금 팔 수 있는 수량 = 기한이 안 지난 lot의 (실물 − 예약) 합. 고객 구매 가능 수량과 같은 규칙(S-31) */
    private int sellable(Long storeId, Long productId) {
        return inventoryLotRepository
                .findByStoreIdAndProductIdAndExpiryDateAfterOrderByExpiryDateAscIdAsc(
                        storeId, productId, LocalDateTime.now(clock))
                .stream()
                .mapToInt(InventoryLot::availableQuantity)
                .sum();
    }

    /** 확정·해제가 건드린 lot들의 상품. 고객용 조회 표를 다시 계산할 대상 */
    private static List<Long> productIdsOf(Map<Long, InventoryLot> lots) {
        return lots.values().stream()
                .map(InventoryLot::getProductId)
                .distinct()
                .toList();
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
