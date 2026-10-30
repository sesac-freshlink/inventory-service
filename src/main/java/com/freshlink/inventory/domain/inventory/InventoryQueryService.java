package com.freshlink.inventory.domain.inventory;

import java.time.Clock;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InventoryQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final InventoryLotRepository inventoryLotRepository;
    private final ProductRepository productRepository;
    private final InventoryCalculationService calculationService;
    private final InventorySearchRepository inventorySearchRepository;
    private final Clock clock;

    public InventoryQueryService(
            InventoryLotRepository inventoryLotRepository,
            ProductRepository productRepository,
            InventoryCalculationService calculationService,
            InventorySearchRepository inventorySearchRepository,
            Clock clock
    ) {
        this.inventoryLotRepository = inventoryLotRepository;
        this.productRepository = productRepository;
        this.calculationService = calculationService;
        this.inventorySearchRepository = inventorySearchRepository;
        this.clock = clock;
    }

    /** 한 페이지 최대 크기. 화면 기본은 DEFAULT_PAGE_SIZE */
    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * S-10 점주 재고 목록 한 페이지. 집계·검색·자르기는 InventorySearchRepository의 SQL 한 번으로 한다.
     * lowStockCount는 검색·필터와 상관없이 점포 전체의 부족 상품 수(화면 위쪽 건수)
     */
    public InventoryPageResponse searchInventory(
            Long storeId, InventorySearchCondition condition, Integer page, Integer size) {
        int pageNumber = page != null ? page : 0;
        int pageSize = size != null ? size : DEFAULT_PAGE_SIZE;
        if (pageNumber < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(InventoryErrorCode.INVALID_PAGE);
        }
        LocalDateTime now = LocalDateTime.now(clock);

        List<InventorySummaryResponse> items = inventorySearchRepository.search(
                storeId, condition, now, pageNumber * pageSize, pageSize);
        long total = inventorySearchRepository.count(storeId, condition, now);
        long lowStockCount = inventorySearchRepository.count(
                storeId, InventorySearchCondition.lowStockOnly(), now);

        return new InventoryPageResponse(
                items,
                pageNumber,
                pageSize,
                total,
                (int) ((total + pageSize - 1) / pageSize),
                lowStockCount
        );
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        return dateTime.atZone(SEOUL).toOffsetDateTime();
    }

    public List<InventoryLotResponse> getLots(Long storeId, Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND);
        }

        LocalDateTime now = LocalDateTime.now(clock);

        return inventoryLotRepository
                .findByStoreIdAndProductIdOrderByExpiryDateAscIdAsc(
                        storeId,
                        productId
                )
                .stream()
                .filter(lot -> lot.getQuantity() > 0)
                .map(lot -> toLotResponse(lot, now))
                .toList();
    }

    private InventoryLotResponse toLotResponse(
            InventoryLot lot,
            LocalDateTime now
    ) {
        InventoryLotStatus status =
                calculationService.calculateStatus(lot, now);

        int availableQuantity =
                status == InventoryLotStatus.EXPIRED
                        ? 0
                        : lot.getQuantity() - lot.getReservedQuantity();

        return new InventoryLotResponse(
                lot.getId(),
                toOffsetDateTime(lot.getExpiryDate()),
                lot.getQuantity(),
                lot.getReservedQuantity(),
                availableQuantity,
                status
        );
    }

    public List<ExpiringInventoryResponse> getExpiring(Long storeId) {
        LocalDateTime now = LocalDateTime.now(clock);

        List<InventoryLot> lots = inventoryLotRepository.findByStoreId(storeId);

        if (lots.isEmpty()) {
            return List.of();
        }

        Map<Long, Product> products = productRepository.findAllById(
                        lots.stream()
                                .map(InventoryLot::getProductId)
                                .collect(Collectors.toSet())
                )
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        return lots.stream()
                .filter(lot -> lot.getQuantity() > 0)
                .filter(lot -> {
                    InventoryLotStatus status =
                            calculationService.calculateStatus(lot, now);

                    return status == InventoryLotStatus.IMMINENT
                            || status == InventoryLotStatus.EXPIRED;
                })
                .sorted(
                        java.util.Comparator
                                .comparing(InventoryLot::getExpiryDate)
                                .thenComparing(InventoryLot::getId)
                )
                .map(lot -> toExpiringResponse(
                        lot,
                        products.get(lot.getProductId()),
                        now
                ))
                .toList();
    }

    private ExpiringInventoryResponse toExpiringResponse(
            InventoryLot lot,
            Product product,
            LocalDateTime now
    ) {
        if (product == null) {
            throw new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND);
        }

        InventoryLotStatus status =
                calculationService.calculateStatus(lot, now);

        return new ExpiringInventoryResponse(
                lot.getId(),
                lot.getProductId(),
                product.getName(),
                toOffsetDateTime(lot.getExpiryDate()),
                status == InventoryLotStatus.EXPIRED,
                lot.getQuantity(),
                lot.getReservedQuantity(),
                lot.getQuantity() - lot.getReservedQuantity()
        );
    }
}
