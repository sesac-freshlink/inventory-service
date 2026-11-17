package com.freshlink.inventory.domain.assistant;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI에 질문과 함께 보내는 점포 하나의 숫자 요약 (문서 4-1). 원장·lot·상품·안전재고만 담는다.
 * 고객·주문 정보 칸은 두지 않는다(국외 리전 처리, 문서 7장). 합계·평균은 코드가 미리 계산해 넣는다.
 * inventory에는 점포 이름이 없어 점포 ID만 넣는다.
 */
public record StoreSummary(Store store, List<ProductSummary> products, List<LotSummary> lots) {

    public record Store(Long storeId, OffsetDateTime now) {
    }

    /**
     * @param available      지금 살 수 있는 수량(기한이 지나지 않은 lot의 실물 − 예약)
     * @param safetyStock    안전재고(설정이 없으면 기본값)
     * @param soldThisMonth  이번 달(1일~지금) 순판매(판매 − 환불 복원)
     * @param soldLastMonth  지난달 순판매
     * @param dailySales14d  최근 14일 날짜별 순판매(오래된 날부터). 14일 판매가 0이면 빈 목록
     * @param inbound30d     최근 30일 입고 수량
     * @param disposed30d    최근 30일 폐기 수량(양수)
     * @param salesByHour7d  최근 7일 시간대별 순판매. 판매가 없으면 빈 맵
     */
    public record ProductSummary(
            Long productId,
            String name,
            String category,
            long price,
            int available,
            int safetyStock,
            int soldThisMonth,
            int soldLastMonth,
            List<Integer> dailySales14d,
            int inbound30d,
            int disposed30d,
            Map<String, Integer> salesByHour7d
    ) {
    }

    /** 임박(24시간 안)·경과 lot만. 수량은 실물 */
    public record LotSummary(Long productId, String name, int quantity, OffsetDateTime expiresAt, String status) {
    }
}
