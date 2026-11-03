package com.freshlink.inventory.domain.adjustment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.inventory.InventoryLot;
import com.freshlink.inventory.domain.inventory.InventoryLotRepository;
import com.freshlink.inventory.domain.inventory.Ledger;
import com.freshlink.inventory.domain.inventory.LedgerRepository;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;
import com.freshlink.inventory.domain.storestock.StockChangePublisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-14 재고 수동 조정·실사. 다른 재고 변경과 달리 쪽지(inventory-commands.fifo)를 거치지 않고 바로 고친다.
 * 그래서 같은 점포 쪽지 처리와 같은 lot을 동시에 고칠 수 있고, 이를 inventory_lot.version(낙관적 락)으로 찾는다.
 *
 * <ul>
 *   <li>조정이 늦게 커밋하면: UPDATE가 0행이라 409 LOT_CHANGED. 점주가 바뀐 수량을 보고 다시 한다</li>
 *   <li>쪽지 처리가 늦게 커밋하면: 그 트랜잭션이 통째로 되돌려지고(processed_message 기록 포함) SQS가 다시 보낸다</li>
 * </ul>
 * 쪽지 형식(common)을 바꾸지 않으려고 조정 쪽지를 새로 만들지 않았다.
 */
@Service
public class AdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final PageRequest RECENT_100 = PageRequest.of(0, 100);

    private final InventoryLotRepository inventoryLotRepository;
    private final AdjustmentRepository adjustmentRepository;
    private final LedgerRepository ledgerRepository;
    private final ProductRepository productRepository;
    private final StockChangePublisher stockChanges;
    private final Clock clock;

    public AdjustmentService(
            InventoryLotRepository inventoryLotRepository,
            AdjustmentRepository adjustmentRepository,
            LedgerRepository ledgerRepository,
            ProductRepository productRepository,
            StockChangePublisher stockChanges,
            Clock clock
    ) {
        this.inventoryLotRepository = inventoryLotRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.ledgerRepository = ledgerRepository;
        this.productRepository = productRepository;
        this.stockChanges = stockChanges;
        this.clock = clock;
    }

    /** lot 수량 변경·조정 기록·원장 ADJUST를 한 트랜잭션으로 남긴다 */
    @Transactional
    public AdjustmentResponse adjust(Long storeId, String userId, AdjustmentRequest request) {
        InventoryLot lot = inventoryLotRepository.findById(request.lotId())
                .orElseThrow(() -> new BusinessException(InventoryErrorCode.LOT_NOT_FOUND));

        if (!lot.getStoreId().equals(storeId)) {
            throw new BusinessException(InventoryErrorCode.LOT_NOT_IN_STORE);
        }

        int delta = request.delta();
        if (delta == 0) {
            throw new BusinessException(InventoryErrorCode.INVALID_QUANTITY);
        }

        int before = lot.getQuantity();
        if (before + delta < lot.getReservedQuantity()) {
            throw new BusinessException(InventoryErrorCode.BELOW_RESERVED);
        }

        lot.adjust(delta);
        try {
            // 여기서 UPDATE ... WHERE id = ? AND version = ?가 바로 나간다. 커밋 때까지 미루면 예외가 이 메서드 밖에서 나서 409로 못 바꾼다
            inventoryLotRepository.saveAndFlush(lot);
        } catch (OptimisticLockingFailureException e) {
            log.warn("조정 충돌: storeId={}, lotId={}, delta={} (읽은 뒤 다른 처리가 lot을 먼저 고침)",
                    storeId, lot.getId(), delta);
            throw new BusinessException(InventoryErrorCode.LOT_CHANGED);
        }

        Adjustment adjustment = adjustmentRepository.save(Adjustment.record(
                storeId,
                lot.getId(),
                before,
                lot.getQuantity(),
                request.reason(),
                request.memo(),
                userId,
                LocalDateTime.now(clock)
        ));

        ledgerRepository.save(Ledger.adjust(
                storeId,
                lot.getId(),
                delta,
                String.valueOf(adjustment.getId())
        ));

        try {
            stockChanges.publish(storeId, List.of(lot.getProductId()));
        } catch (PessimisticLockingFailureException e) {
            // 같은 상품의 다른 lot을 쪽지 처리가 동시에 바꾸며 서로 기다리면(교착) MySQL이 한쪽을 되돌린다.
            // 쪽지 쪽이면 SQS가 다시 보내고, 조정 쪽이면 같은 409로 점주가 다시 하게 한다
            log.warn("조정 충돌(교착): storeId={}, lotId={}, delta={}", storeId, lot.getId(), delta);
            throw new BusinessException(InventoryErrorCode.LOT_CHANGED);
        }

        log.info("재고 조정: adjustmentId={}, storeId={}, lotId={}, {} -> {}, reason={}",
                adjustment.getId(), storeId, lot.getId(), before, lot.getQuantity(), request.reason());

        return new AdjustmentResponse(
                adjustment.getId(),
                lot.getId(),
                delta,
                before,
                lot.getQuantity()
        );
    }

    /** 최신순 최근 100건 */
    @Transactional(readOnly = true)
    public List<AdjustmentHistoryResponse> getAdjustments(Long storeId) {
        List<Adjustment> adjustments = adjustmentRepository.findRecent(storeId, RECENT_100);
        if (adjustments.isEmpty()) {
            return List.of();
        }

        Map<Long, InventoryLot> lots = inventoryLotRepository
                .findAllById(adjustments.stream().map(Adjustment::getLotId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(InventoryLot::getId, Function.identity()));

        Map<Long, Product> products = productRepository
                .findAllById(lots.values().stream().map(InventoryLot::getProductId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        return adjustments.stream()
                .map(adjustment -> toHistory(adjustment, lots, products))
                .toList();
    }

    private AdjustmentHistoryResponse toHistory(
            Adjustment adjustment,
            Map<Long, InventoryLot> lots,
            Map<Long, Product> products
    ) {
        InventoryLot lot = lots.get(adjustment.getLotId());
        if (lot == null) {
            throw new BusinessException(InventoryErrorCode.LOT_NOT_FOUND);
        }
        Product product = products.get(lot.getProductId());
        if (product == null) {
            throw new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND);
        }

        return new AdjustmentHistoryResponse(
                adjustment.getId(),
                lot.getId(),
                product.getId(),
                product.getName(),
                lot.getExpiryDate().atZone(SEOUL).toOffsetDateTime(),
                adjustment.getDelta(),
                adjustment.getQuantityBefore(),
                adjustment.getQuantityAfter(),
                adjustment.getReason(),
                adjustment.getMemo(),
                adjustment.getCreatedAt().atZone(SEOUL).toOffsetDateTime()
        );
    }
}
