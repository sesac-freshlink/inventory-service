package com.freshlink.inventory.domain.product;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.freshlink.inventory.domain.inventory.LedgerRepository;
import com.freshlink.inventory.domain.inventory.LedgerType;
import com.freshlink.inventory.domain.inventory.ProductLedgerSum;
import com.freshlink.inventory.domain.recommendation.ProductRecommendationRepository;

import org.springframework.stereotype.Component;

/**
 * U-10 고객 목록·상세에 붙는 표시: 추천(점주가 고름), 신상품(첫 입고 7일 안), 추천순에 쓰는 최근 7일 순판매.
 * 점포 하나에 대해 쿼리 세 번으로 한꺼번에 읽는다(상품마다 묻지 않는다).
 */
@Component
public class StoreProductBadges {

    /** 신상품: 그 점포에 처음 입고된 지 이 기간 안 */
    static final Duration NEW_WINDOW = Duration.ofDays(7);
    /** 추천순: 이 기간의 순판매 */
    static final Duration POPULAR_WINDOW = Duration.ofDays(7);

    private final ProductRecommendationRepository recommendationRepository;
    private final LedgerRepository ledgerRepository;

    public StoreProductBadges(
            ProductRecommendationRepository recommendationRepository,
            LedgerRepository ledgerRepository
    ) {
        this.recommendationRepository = recommendationRepository;
        this.ledgerRepository = ledgerRepository;
    }

    public Snapshot load(Long storeId, LocalDateTime now) {
        Map<Long, LocalDateTime> firstInbound = new HashMap<>();
        ledgerRepository.firstInboundByProduct(storeId)
                .forEach(row -> firstInbound.put(row.productId(), row.firstInboundAt()));

        // 판매는 음수, 환불 복원은 양수라 둘을 더하면 −(순판매). 끝은 "미만"이라 지금 막 생긴 원장까지 넣으려고 1초 뒤로 둔다
        Map<Long, Integer> sold = new HashMap<>();
        for (ProductLedgerSum sum : ledgerRepository.sumByProductAndType(
                storeId, now.minus(POPULAR_WINDOW), now.plusSeconds(1))) {
            if (sum.type() == LedgerType.SALE || sum.type() == LedgerType.RETURN) {
                sold.merge(sum.productId(), Math.toIntExact(-sum.quantity()), Integer::sum);
            }
        }

        return new Snapshot(recommendationRepository.findProductIds(storeId), firstInbound, sold, now);
    }

    public record Snapshot(
            Set<Long> recommended,
            Map<Long, LocalDateTime> firstInbound,
            Map<Long, Integer> soldRecently,
            LocalDateTime now
    ) {
        public boolean isRecommended(Long productId) {
            return recommended.contains(productId);
        }

        /** 한 번도 입고되지 않은 상품은 신상품이 아니다 */
        public boolean isNew(Long productId) {
            LocalDateTime first = firstInbound.get(productId);
            return first != null && first.isAfter(now.minus(NEW_WINDOW));
        }

        public int soldRecently(Long productId) {
            return Math.max(0, soldRecently.getOrDefault(productId, 0));
        }
    }
}
