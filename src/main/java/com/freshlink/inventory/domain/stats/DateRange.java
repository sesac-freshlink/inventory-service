package com.freshlink.inventory.domain.stats;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 통계·판매 순위의 조회 기간. 한국 날짜이고 from·to 둘 다 포함한다.
 * to를 빼면 오늘, from을 빼면 to까지 7일. 시작일이 종료일보다 늦으면 400 INVALID_DATE_RANGE
 */
record DateRange(LocalDate start, LocalDate end) {

    /** from을 주지 않을 때 기간 길이(to 포함) */
    static final int DEFAULT_DAYS = 7;

    static DateRange of(LocalDate from, LocalDate to, Clock clock) {
        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1);
        if (start.isAfter(end)) {
            throw new BusinessException(InventoryErrorCode.INVALID_DATE_RANGE);
        }
        return new DateRange(start, end);
    }

    /** 원장 조회 시작(포함) = 시작일 0시 */
    LocalDateTime fromInclusive() {
        return start.atStartOfDay();
    }

    /** 원장 조회 끝(제외) = 종료일 다음 날 0시 */
    LocalDateTime toExclusive() {
        return end.plusDays(1).atStartOfDay();
    }
}
