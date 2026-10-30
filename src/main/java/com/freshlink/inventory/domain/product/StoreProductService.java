package com.freshlink.inventory.domain.product;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.common.search.LikePattern;
import com.freshlink.inventory.domain.storestock.StoreProductRow;
import com.freshlink.inventory.domain.storestock.StoreProductStockRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * U-10 고객용 점포 상품 목록·상세·카테고리 (공개). 수량은 고객용 조회 표(store_product_stock)에서 읽고,
 * 추천·신상품·추천순 값은 StoreProductBadges가 점포 단위로 한 번에 읽는다.
 */
@Service
@Transactional(readOnly = true)
public class StoreProductService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final StoreProductStockRepository storeProductStockRepository;
    private final ProductRepository productRepository;
    private final StoreProductBadges badges;
    private final Clock clock;

    public StoreProductService(
            StoreProductStockRepository storeProductStockRepository,
            ProductRepository productRepository,
            StoreProductBadges badges,
            Clock clock
    ) {
        this.storeProductStockRepository = storeProductStockRepository;
        this.productRepository = productRepository;
        this.badges = badges;
        this.clock = clock;
    }

    /**
     * 검색어·카테고리는 DB가 거르고, 추천·신상품 거르기와 추천순 정렬은 점포 단위로 읽은 값으로 한다.
     * 입고가 한 번도 없는 점포도 상품을 전부 품절로 내린다(명세 4장 U-10). 조회 표에 행이 없는 상품은 0이다
     */
    public List<StoreProductResponse> getStoreProducts(Long storeId, StoreProductQuery query) {
        LocalDateTime now = LocalDateTime.now(clock);
        StoreProductBadges.Snapshot snapshot = badges.load(storeId, now);

        Stream<StoreProductRow> rows = storeProductStockRepository
                .findStoreProducts(storeId, LikePattern.contains(query.q()), query.category())
                .stream();
        if (query.recommendedOnly()) {
            rows = rows.filter(row -> snapshot.isRecommended(row.productId()));
        }
        if (query.newOnly()) {
            rows = rows.filter(row -> snapshot.isNew(row.productId()));
        }
        if (query.sort() == StoreProductSort.POPULAR) {
            // 쿼리가 이미 상품명 순이라 안정 정렬이면 판매 수가 같을 때 상품명 순이 유지된다
            rows = rows.sorted(Comparator.comparingInt(
                    (StoreProductRow row) -> snapshot.soldRecently(row.productId())).reversed());
        }

        return rows.map(row -> {
            int availableQuantity = row.availableAt(now);
            return new StoreProductResponse(
                    row.productId(),
                    row.name(),
                    row.category(),
                    row.price(),
                    row.imageUrl(),
                    availableQuantity,
                    availableQuantity == 0,
                    snapshot.isRecommended(row.productId()),
                    snapshot.isNew(row.productId()),
                    snapshot.isFirstCome(row.productId())
            );
        }).toList();
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
        StoreProductBadges.Snapshot snapshot = badges.load(storeId, now);

        return new StoreProductDetailResponse(
                row.productId(),
                row.name(),
                row.category(),
                row.price(),
                row.imageUrl(),
                availableQuantity,
                availableQuantity == 0,
                availableQuantity == 0 ? null : row.validUntil().atZone(SEOUL).toOffsetDateTime(),
                snapshot.isRecommended(row.productId()),
                snapshot.isNew(row.productId()),
                snapshot.isFirstCome(row.productId())
        );
    }

    /** 카테고리 칩. 상품 전체의 카테고리를 가나다순으로 (점포와 상관없이 같다) */
    public List<String> getCategories() {
        return productRepository.findDistinctCategories();
    }
}
