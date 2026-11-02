package com.freshlink.inventory.domain.inventory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
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
    private final LedgerRepository ledgerRepository;
    private final Clock clock;

    public InventoryQueryService(
            InventoryLotRepository inventoryLotRepository,
            ProductRepository productRepository,
            InventoryCalculationService calculationService,
            InventorySearchRepository inventorySearchRepository,
            LedgerRepository ledgerRepository,
            Clock clock
    ) {
        this.inventoryLotRepository = inventoryLotRepository;
        this.productRepository = productRepository;
        this.calculationService = calculationService;
        this.inventorySearchRepository = inventorySearchRepository;
        this.ledgerRepository = ledgerRepository;
        this.clock = clock;
    }

    /** 한 페이지 최대 크기. 화면 기본은 DEFAULT_PAGE_SIZE */
    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 20;
    /** 재고 소진 예상: 이 기간(일)의 하루 평균 순판매로 남은 일수를 센다 */
    static final int SALES_PACE_DAYS = 7;

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

        Map<Long, Integer> netSold = netSoldByProduct(storeId, now);
        List<InventorySummaryResponse> items = inventorySearchRepository.search(
                        storeId, condition, now, pageNumber * pageSize, pageSize).stream()
                .map(item -> item.withSalesPace(netSold.getOrDefault(item.productId(), 0), SALES_PACE_DAYS))
                .toList();
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

    /**
     * 재고 소진 예상에 쓰는 최근 SALES_PACE_DAYS일 순판매(판매 − 환불). 판매는 음수, 환불 복원은 양수라 둘을 더하면 −(순판매).
     * 끝은 "미만"이라 지금 막 생긴 원장까지 넣으려고 1초 뒤로 둔다(추천순과 같다). 점포 하나에 GROUP BY 쿼리 한 번
     */
    private Map<Long, Integer> netSoldByProduct(Long storeId, LocalDateTime now) {
        Map<Long, Integer> sold = new HashMap<>();
        for (ProductLedgerSum sum : ledgerRepository.sumByProductAndType(
                storeId, now.minusDays(SALES_PACE_DAYS), now.plusSeconds(1))) {
            if (sum.type() == LedgerType.SALE || sum.type() == LedgerType.RETURN) {
                sold.merge(sum.productId(), Math.toIntExact(-sum.quantity()), Integer::sum);
            }
        }
        return sold;
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
