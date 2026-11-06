package com.freshlink.inventory.domain.product;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.freshlink.inventory.domain.clearance.ClearancePolicy;
import com.freshlink.inventory.domain.storestock.StoreProductRow;

/**
 * 고객 화면에 보일 수량을 조회 표 한 행에서 계산한다(목록·상세 공통). 마감세일 규칙은 {@link ClearancePolicy}.
 * <ul>
 *   <li>기본 수량(availableQuantity): 24시간 밖 lot의 몫. 기본 주문은 마감세일 lot을 건너뛴다.</li>
 *   <li>마감세일 수량: 24시간 안 lot의 몫. 판매 시간(06~23시) 밖이면 0.</li>
 *   <li>선착순 상품은 마감세일이 없고, 24시간 안 lot도 기본 수량에 들어간다.</li>
 *   <li>품절은 기본·마감세일 둘 다 0일 때다.</li>
 * </ul>
 */
record StoreProductStockView(
        int availableQuantity,
        int clearanceQuantity,
        Long clearancePrice,
        OffsetDateTime nearestExpiry,
        OffsetDateTime clearanceExpiry
) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    static StoreProductStockView of(StoreProductRow row, boolean firstCome, LocalDateTime now) {
        int total = row.availableAt(now);
        if (total == 0) {
            return new StoreProductStockView(0, 0, null, null, null);
        }
        int clearance = Math.min(row.clearanceAt(now), total);
        if (firstCome) {
            return new StoreProductStockView(total, 0, null, toOffset(earlier(row.normalExpiry(), row.clearanceExpiry())), null);
        }
        int normal = total - clearance;
        int sellableClearance = ClearancePolicy.inSaleHours(now) ? clearance : 0;
        return new StoreProductStockView(
                normal,
                sellableClearance,
                sellableClearance == 0 ? null : ClearancePolicy.price(row.price()),
                normal == 0 ? null : toOffset(row.normalExpiry()),
                sellableClearance == 0 ? null : toOffset(row.clearanceExpiry())
        );
    }

    boolean soldOut() {
        return availableQuantity == 0 && clearanceQuantity == 0;
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
