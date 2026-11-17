package com.freshlink.inventory.domain.clearance;

import java.time.LocalDateTime;

/**
 * 점포·분류마다 점주가 정하는 마감세일 설정(11/17). 정한 적이 없으면 {@link #DEFAULT}(적용, 30%, 06~23시)다.
 * <ul>
 *   <li>enabled=false: 그 분류는 마감세일을 만들지 않는다. 선착순 상품처럼 24시간 안 lot도 기본가로 판다.</li>
 *   <li>판매 시간: 한국 시각 saleStartHour시 정각 이상 ~ saleEndHour시 정각 미만(끝 24 = 자정). 그 밖에는 마감세일 수량 0.</li>
 *   <li>단가: 정가에서 discountPercent% 할인하고 10원 단위로 내린다. 10원보다 작아지면 10원(0원 결제를 막는다).</li>
 * </ul>
 * 마감세일 lot의 기준(유통기한 24시간 안)은 점포와 상관없이 {@link ClearancePolicy}에 있다.
 */
public record ClearanceSetting(boolean enabled, int discountPercent, int saleStartHour, int saleEndHour) {

    public static final ClearanceSetting DEFAULT = new ClearanceSetting(true, 30, 6, 23);

    /** 할인율 범위(11/17 사용자: 10~90%). DB CHECK(V14)와 같다 */
    public static final int MIN_DISCOUNT_PERCENT = 10;
    public static final int MAX_DISCOUNT_PERCENT = 90;
    /** 할인가의 바닥. 정가가 이보다 싸면 정가 */
    public static final long MIN_PRICE = 10L;

    /** 마감세일을 팔 수 있는 시각인지(시작 시 정각 이상, 끝 시 정각 미만) */
    public boolean inSaleHours(LocalDateTime now) {
        int hour = now.getHour();
        return hour >= saleStartHour && hour < saleEndHour;
    }

    /** 마감세일 단가: 할인하고 10원 단위로 내린다. 최소 10원, 정가를 넘지 않는다 */
    public long price(long basePrice) {
        long discounted = basePrice * (100 - discountPercent) / 100 / 10 * 10;
        return Math.min(basePrice, Math.max(MIN_PRICE, discounted));
    }
}
