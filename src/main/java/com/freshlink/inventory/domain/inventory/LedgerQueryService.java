package com.freshlink.inventory.domain.inventory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class LedgerQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final Pageable RECENT_100 =
            PageRequest.of(0, 100);

    private final LedgerRepository ledgerRepository;
    private final InventoryLotRepository inventoryLotRepository;
    private final ProductRepository productRepository;

    public LedgerQueryService(
            LedgerRepository ledgerRepository,
            InventoryLotRepository inventoryLotRepository,
            ProductRepository productRepository
    ) {
        this.ledgerRepository = ledgerRepository;
        this.inventoryLotRepository = inventoryLotRepository;
        this.productRepository = productRepository;
    }

    public List<LedgerResponse> getLedger(
            Long storeId,
            Long productId,
            LedgerType type,
            LocalDate from,
            LocalDate to
    ) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(
                    InventoryErrorCode.INVALID_DATE_RANGE
            );
        }

        LocalDateTime fromDateTime =
                from == null ? null : from.atStartOfDay();

        LocalDateTime toExclusive =
                to == null ? null : to.plusDays(1).atStartOfDay();

        List<Ledger> ledgers = ledgerRepository.findLedger(
                storeId,
                productId,
                type,
                fromDateTime,
                toExclusive,
                RECENT_100
        );

        return toResponses(ledgers);
    }

    private List<LedgerResponse> toResponses(List<Ledger> ledgers) {
        if (ledgers.isEmpty()) {
            return List.of();
        }

        Map<Long, InventoryLot> lotsById = inventoryLotRepository
                .findAllById(
                        ledgers.stream()
                                .map(Ledger::getLotId)
                                .distinct()
                                .toList()
                )
                .stream()
                .collect(Collectors.toMap(
                        InventoryLot::getId,
                        Function.identity()
                ));

        Map<Long, Product> productsById = productRepository
                .findAllById(
                        lotsById.values().stream()
                                .map(InventoryLot::getProductId)
                                .distinct()
                                .toList()
                )
                .stream()
                .collect(Collectors.toMap(
                        Product::getId,
                        Function.identity()
                ));

        return ledgers.stream()
                .map(ledger -> toResponse(
                        ledger,
                        lotsById,
                        productsById
                ))
                .toList();
    }

    private LedgerResponse toResponse(
            Ledger ledger,
            Map<Long, InventoryLot> lotsById,
            Map<Long, Product> productsById
    ) {
        InventoryLot lot = lotsById.get(ledger.getLotId());

        if (lot == null) {
            throw new BusinessException(
                    InventoryErrorCode.LOT_NOT_FOUND
            );
        }

        Product product = productsById.get(lot.getProductId());

        if (product == null) {
            throw new BusinessException(
                    InventoryErrorCode.PRODUCT_NOT_FOUND
            );
        }

        return new LedgerResponse(
                ledger.getId(),
                ledger.getType(),
                product.getId(),
                product.getName(),
                lot.getId(),
                toOffsetDateTime(lot.getExpiryDate()),
                ledger.getQuantity(),
                ledger.getRefId(),
                toOffsetDateTime(ledger.getCreatedAt())
        );
    }

    private OffsetDateTime toOffsetDateTime(
            LocalDateTime dateTime
    ) {
        return dateTime.atZone(SEOUL).toOffsetDateTime();
    }
}