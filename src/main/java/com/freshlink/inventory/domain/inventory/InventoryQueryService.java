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
import com.freshlink.inventory.common.search.LikePattern;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;
import com.freshlink.inventory.domain.product.StoreProductDetailResponse;
import com.freshlink.inventory.domain.product.StoreProductResponse;
import com.freshlink.inventory.domain.storestock.StoreProductRow;
import com.freshlink.inventory.domain.storestock.StoreProductStockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InventoryQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final InventoryLotRepository inventoryLotRepository;
    private final ProductRepository productRepository;
    private final InventoryCalculationService calculationService;
    private final StoreProductStockRepository storeProductStockRepository;
    private final InventorySearchRepository inventorySearchRepository;
    private final Clock clock;

    public InventoryQueryService(
            InventoryLotRepository inventoryLotRepository,
            ProductRepository productRepository,
            InventoryCalculationService calculationService,
            StoreProductStockRepository storeProductStockRepository,
            InventorySearchRepository inventorySearchRepository,
            Clock clock
    ) {
        this.inventoryLotRepository = inventoryLotRepository;
        this.productRepository = productRepository;
        this.calculationService = calculationService;
        this.storeProductStockRepository = storeProductStockRepository;
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

    /**
     * U-10 고객 상품 목록. lot을 합산하지 않고 고객용 재고 조회 표(store_product_stock)만 읽는다(CQRS).
     * 조회 표는 재고를 바꾸는 처리가 같은 트랜잭션에서 다시 계산해 둔다. 값이 맞는 시각이 지난 행은 품절로 본다.
     * 상품과 조회 표를 LEFT JOIN 한 쿼리 한 번이고, q가 있으면 상품명으로 거른다(DB가 거르고 상품명 순으로 준다)
     */
    public List<StoreProductResponse> getStoreProducts(Long storeId, String q) {
        LocalDateTime now = LocalDateTime.now(clock);

        // 입고가 한 번도 없는 점포도 상품을 전부 품절로 내린다(명세 4장 U-10). 조회 표에 행이 없는 상품은 0이다
        return storeProductStockRepository.findStoreProducts(storeId, LikePattern.contains(q))
                .stream()
                .map(row -> {
                    int availableQuantity = row.availableAt(now);
                    return new StoreProductResponse(
                            row.productId(),
                            row.name(),
                            row.category(),
                            row.price(),
                            row.imageUrl(),
                            availableQuantity,
                            availableQuantity == 0
                    );
                })
                .toList();
    }

    /**
     * U-10 고객 상품 상세. 목록과 같은 조회 표 행을 읽는다. 먼저 받게 될 상품의 유통기한은 조회 표의 valid_until
     * (남은 재고가 있는 미경과 lot 중 가장 빠른 기한, 예약은 FEFO)이고, 품절이면 보여 주지 않는다
     */
    public StoreProductDetailResponse getStoreProduct(Long storeId, Long productId) {
        LocalDateTime now = LocalDateTime.now(clock);
        StoreProductRow row = storeProductStockRepository.findStoreProduct(storeId, productId)
                .orElseThrow(() -> new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND));
        int availableQuantity = row.availableAt(now);

        return new StoreProductDetailResponse(
                row.productId(),
                row.name(),
                row.category(),
                row.price(),
                row.imageUrl(),
                availableQuantity,
                availableQuantity == 0,
                availableQuantity == 0 ? null : toOffsetDateTime(row.validUntil())
        );
    }
}
