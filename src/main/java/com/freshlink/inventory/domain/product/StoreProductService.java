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

    /** 무한 스크롤 한 묶음 최대 크기. 점주 재고 목록(S-10)과 같다 */
    static final int MAX_PAGE_SIZE = 100;

    /**
     * 무한 스크롤용. 아래 목록을 거르고 정렬한 뒤 page번째 묶음(size개)만 돌려준다. page·size가 둘 다 없으면 전체다
     * (시뮬레이터 등 지금 부르는 쪽이 그대로 쓴다). 한쪽만 오거나 범위를 벗어나면 400 INVALID_PAGE.
     * 상품 수가 점포당 수십 개라 거르기·정렬은 지금처럼 한 번 하고 자르기만 한다
     */
    public List<StoreProductResponse> getStoreProducts(
            Long storeId, StoreProductQuery query, Integer page, Integer size) {
        if (page == null && size == null) {
            return getStoreProducts(storeId, query);
        }
        if (page == null || size == null || page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(InventoryErrorCode.INVALID_PAGE);
        }
        List<StoreProductResponse> all = getStoreProducts(storeId, query);
        long from = (long) page * size;
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList((int) from, (int) Math.min(from + size, all.size()));
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
            StoreProductStockView stock = StoreProductStockView.of(row, snapshot.isFirstCome(row.productId()), now);
            return new StoreProductResponse(
                    row.productId(),
                    row.name(),
                    row.category(),
                    row.price(),
                    row.imageUrl(),
                    stock.availableQuantity(),
                    stock.soldOut(),
                    snapshot.isRecommended(row.productId()),
                    snapshot.isNew(row.productId()),
                    snapshot.isFirstCome(row.productId()),
                    stock.nearestExpiry(),
                    stock.clearanceQuantity(),
                    stock.clearancePrice(),
                    stock.clearanceExpiry()
            );
        }).toList();
    }

    /**
     * U-10 고객 상품 상세. 목록과 같은 조회 표 행·같은 계산(StoreProductStockView)이다. nearestExpiry는 기본 주문으로 먼저 받게 될
     * 상품의 유통기한(24시간 밖 lot 중 가장 빠른 기한, 예약은 FEFO), 마감세일은 따로 clearance* 칸에 준다
     */
    public StoreProductDetailResponse getStoreProduct(Long storeId, Long productId) {
        LocalDateTime now = LocalDateTime.now(clock);
        StoreProductRow row = storeProductStockRepository.findStoreProduct(storeId, productId)
                .orElseThrow(() -> new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND));
        StoreProductBadges.Snapshot snapshot = badges.load(storeId, now);
        StoreProductStockView stock = StoreProductStockView.of(row, snapshot.isFirstCome(row.productId()), now);

        return new StoreProductDetailResponse(
                row.productId(),
                row.name(),
                row.category(),
                row.price(),
                row.imageUrl(),
                stock.availableQuantity(),
                stock.soldOut(),
                stock.nearestExpiry(),
                snapshot.isRecommended(row.productId()),
                snapshot.isNew(row.productId()),
                snapshot.isFirstCome(row.productId()),
                stock.clearanceQuantity(),
                stock.clearancePrice(),
                stock.clearanceExpiry()
        );
    }

    /** 카테고리 칩. 상품 전체의 카테고리를 가나다순으로 (점포와 상관없이 같다) */
    public List<String> getCategories() {
        return productRepository.findDistinctCategories();
    }
}
