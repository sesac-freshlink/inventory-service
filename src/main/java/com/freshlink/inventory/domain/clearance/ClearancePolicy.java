package com.freshlink.inventory.domain.clearance;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 마감세일 규칙(11/6 김경우와 확정). 한곳에 모아 두고 예약·고객 응답·조회 표가 같이 쓴다.
 * <ul>
 *   <li>마감세일 lot: 유통기한까지 {@link #WINDOW} 이내(지금 &lt; 기한 ≤ 지금 + 24시간). 점주 '임박'·임박 알림과 같은 기준.</li>
 *   <li>마감세일 lot은 기본가로 팔지 않는다. 기본 주문은 24시간 밖 lot에서만 잡는다.</li>
 *   <li>선착순 상품은 마감세일을 만들지 않고, 24시간 안 lot도 기본 예약 대상이다(안 그러면 임박 재고가 안 팔린다).</li>
 *   <li>판매 시간: 한국 시각 {@link #SALE_START}~{@link #SALE_END}. 그 밖에는 마감세일 수량을 0으로 보이고 예약도 받지 않는다
 *       (카드사 점검 시간을 피하고 픽업할 시간을 남긴다).</li>
 *   <li>단가: 정가의 {@link #DISCOUNT_PERCENT}% 할인, 10원 단위 내림.</li>
 * </ul>
 * 시각은 모두 한국 시각 LocalDateTime(서비스 Clock이 Asia/Seoul)이다.
 */
public final class ClearancePolicy {

    public static final Duration WINDOW = Duration.ofHours(24);
    public static final int DISCOUNT_PERCENT = 30;
    public static final LocalTime SALE_START = LocalTime.of(6, 0);
    public static final LocalTime SALE_END = LocalTime.of(23, 0);

    private ClearancePolicy() {
    }

    /** 이 시각보다 기한이 늦은 lot은 마감세일이 아니다(기본 예약 대상) */
    public static LocalDateTime windowEnd(LocalDateTime now) {
        return now.plus(WINDOW);
    }

    /** 기한이 지나지 않았고 24시간 안에 끝나는 lot */
    public static boolean isClearanceLot(LocalDateTime expiry, LocalDateTime now) {
        return expiry.isAfter(now) && !expiry.isAfter(windowEnd(now));
    }

    /** 마감세일을 팔 수 있는 시각인지(06:00 이상 23:00 미만) */
    public static boolean inSaleHours(LocalDateTime now) {
        LocalTime t = now.toLocalTime();
        return !t.isBefore(SALE_START) && t.isBefore(SALE_END);
    }

    /** 마감세일 단가: 정가에서 30% 할인하고 10원 단위로 내린다 */
    public static long price(long basePrice) {
        long discounted = basePrice * (100 - DISCOUNT_PERCENT) / 100;
        return discounted / 10 * 10;
    }
}
