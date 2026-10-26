package com.freshlink.inventory.domain.disposal;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.inventory.InventoryLot;
import com.freshlink.inventory.domain.inventory.InventoryLotRepository;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-33 폐기 요청 이력. 폐기는 202로 접수만 되고 결과(DONE·SKIPPED)는 DISPOSE 쪽지를 처리한 뒤에 정해지므로,
 * 점주가 그 결과를 볼 수 있게 disposal 표를 최신순으로 읽어 준다. 상품명·유통기한은 lot → product로 채운다.
 */
@Service
@Transactional(readOnly = true)
public class DisposalQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final Pageable RECENT_100 = PageRequest.of(0, 100);

    private final DisposalRepository disposalRepository;
    private final InventoryLotRepository inventoryLotRepository;
    private final ProductRepository productRepository;

    public DisposalQueryService(
            DisposalRepository disposalRepository,
            InventoryLotRepository inventoryLotRepository,
            ProductRepository productRepository
    ) {
        this.disposalRepository = disposalRepository;
        this.inventoryLotRepository = inventoryLotRepository;
        this.productRepository = productRepository;
    }

    /** 최근 100건 최신순. status를 주면 그 상태만 */
    public List<DisposalHistoryResponse> getDisposals(Long storeId, DisposalStatus status) {
        List<Disposal> disposals = status == null
                ? disposalRepository.findByStoreIdOrderByIdDesc(storeId, RECENT_100)
                : disposalRepository.findByStoreIdAndStatusOrderByIdDesc(storeId, status, RECENT_100);

        if (disposals.isEmpty()) {
            return List.of();
        }

        Map<Long, InventoryLot> lotsById = inventoryLotRepository
                .findAllById(disposals.stream().map(Disposal::getLotId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(InventoryLot::getId, Function.identity()));

        Map<Long, Product> productsById = productRepository
                .findAllById(lotsById.values().stream().map(InventoryLot::getProductId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        return disposals.stream()
                .map(disposal -> toResponse(disposal, lotsById, productsById))
                .toList();
    }

    private DisposalHistoryResponse toResponse(
            Disposal disposal,
            Map<Long, InventoryLot> lotsById,
            Map<Long, Product> productsById
    ) {
        InventoryLot lot = lotsById.get(disposal.getLotId());
        if (lot == null) {
            throw new BusinessException(InventoryErrorCode.LOT_NOT_FOUND);
        }

        Product product = productsById.get(lot.getProductId());
        if (product == null) {
            throw new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND);
        }

        return new DisposalHistoryResponse(
                disposal.getId(),
                lot.getId(),
                product.getId(),
                product.getName(),
                toOffsetDateTime(lot.getExpiryDate()),
                disposal.getQuantity(),
                disposal.getStatus(),
                toOffsetDateTime(disposal.getCreatedAt()),
                toOffsetDateTime(disposal.getProcessedAt())
        );
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(SEOUL).toOffsetDateTime();
    }
}
