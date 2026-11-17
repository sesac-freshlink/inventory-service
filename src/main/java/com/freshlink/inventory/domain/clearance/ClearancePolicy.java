package com.freshlink.inventory.domain.clearance;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 마감세일 lot의 기준(11/6 김경우와 확정). 예약·고객 응답·조회 표가 같이 쓴다.
 * <ul>
 *   <li>마감세일 lot: 유통기한까지 {@link #WINDOW} 이내(지금 &lt; 기한 ≤ 지금 + 24시간). 점주 '임박'·임박 알림과 같은 기준.</li>
 *   <li>마감세일 lot은 기본가로 팔지 않는다. 기본 주문은 24시간 밖 lot에서만 잡는다.</li>
 *   <li>선착순 상품은 마감세일을 만들지 않고, 24시간 안 lot도 기본 예약 대상이다(안 그러면 임박 재고가 안 팔린다).</li>
 * </ul>
 * 적용 여부·할인율·판매 시간은 점포·분류마다 점주가 정한다(11/17) → {@link ClearanceSetting}.
 * 시각은 모두 한국 시각 LocalDateTime(서비스 Clock이 Asia/Seoul)이다.
 */
public final class ClearancePolicy {

    public static final Duration WINDOW = Duration.ofHours(24);

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
}
