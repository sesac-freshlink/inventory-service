package com.freshlink.inventory.domain.product;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.clearance.ClearancePolicy;
import com.freshlink.inventory.domain.firstcome.FirstComeSaleRepository;
import com.freshlink.inventory.domain.storestock.StoreProductStock;
import com.freshlink.inventory.domain.storestock.StoreProductStockRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주변 점포 재고(11/4 멘토링). 품절인 고객에게 이 상품이 남아 있는 다른 점포를 보여 주려고, 고객용 조회 표
 * (store_product_stock)에서 상품 하나의 점포별 구매 가능 수량을 읽는다. 고객 상품 목록과 같은 표·같은 규칙이라
 * 목록에서 보이는 수량과 어긋나지 않는다. 거리 계산은 점포 위치를 가진 화면(member 점포 API)이 한다.
 */
@Service
@Transactional(readOnly = true)
public class ProductStoreStockService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final StoreProductStockRepository storeProductStockRepository;
    private final ProductRepository productRepository;
    private final FirstComeSaleRepository firstComeSaleRepository;
    private final Clock clock;

    public ProductStoreStockService(
            StoreProductStockRepository storeProductStockRepository,
            ProductRepository productRepository,
            FirstComeSaleRepository firstComeSaleRepository,
            Clock clock
    ) {
        this.storeProductStockRepository = storeProductStockRepository;
        this.productRepository = productRepository;
        this.firstComeSaleRepository = firstComeSaleRepository;
        this.clock = clock;
    }

    /** 지금 살 수 있는(1개 이상) 점포만, 많이 남은 순(같으면 점포 ID 순). 없는 상품은 404, 어디에도 없으면 빈 목록 */
    public List<StoreStockResponse> getStoresWithStock(Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return storeProductStockRepository.findByProductId(productId).stream()
                .map(stock -> toResponse(stock, now))
                .filter(response -> response != null)
                .sorted(Comparator.comparingInt(StoreStockResponse::availableQuantity).reversed()
                        .thenComparing(StoreStockResponse::storeId))
                .toList();
    }

    /**
     * 상품 목록과 같은 규칙(StoreProductStockView)으로 그 점포에서 지금 살 수 있는 수량을 센다. 기본 수량에 마감세일 수량을
     * 더하되, 판매 시간(06~23시) 밖이면 마감세일 몫은 뺀다. 선착순 상품은 마감세일이 없어 전부 센다.
     * 유통기한은 그중 가장 빠른 것이다. 살 수 있는 게 없으면 null
     */
    private StoreStockResponse toResponse(StoreProductStock stock, LocalDateTime now) {
        int total = stock.availableAt(now);
        if (total == 0) {
            return null;
        }
        if (firstComeSaleRepository.find(stock.getStoreId(), stock.getProductId()).isPresent()) {
            return new StoreStockResponse(stock.getStoreId(), total,
                    toOffset(earlier(stock.getNormalExpiry(), stock.getClearanceExpiry())));
        }
        int clearance = Math.min(stock.clearanceAt(now), total);
        int normal = total - clearance;
        int sellableClearance = ClearancePolicy.inSaleHours(now) ? clearance : 0;
        int quantity = normal + sellableClearance;
        if (quantity == 0) {
            return null;
        }
        return new StoreStockResponse(stock.getStoreId(), quantity, toOffset(earlier(
                normal == 0 ? null : stock.getNormalExpiry(),
                sellableClearance == 0 ? null : stock.getClearanceExpiry())));
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    private static OffsetDateTime toOffset(LocalDateTime t) {
        return t == null ? null : t.atZone(SEOUL).toOffsetDateTime();
    }
}
