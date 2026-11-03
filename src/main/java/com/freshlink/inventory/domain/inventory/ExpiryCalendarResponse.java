package com.freshlink.inventory.domain.inventory;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "유통기한 달력. month(yyyy-MM)에 기한이 끝나는 lot(수량이 남은 것)을 날짜별로 묶는다. "
        + "lot이 있는 날짜만, 날짜 순. 상태는 지금 시각으로 계산한다")
public record ExpiryCalendarResponse(
        String month,
        List<Day> days
) {

    @Schema(description = "하루. quantity는 그날 끝나는 lot의 실물 수량 합")
    public record Day(
            LocalDate date,
            int lotCount,
            int quantity,
            List<Lot> lots
    ) {
    }

    @Schema(description = "그날 끝나는 lot 하나. 같은 날은 기한 시각 → 상품명 순")
    public record Lot(
            Long lotId,
            Long productId,
            String productCode,
            String name,
            OffsetDateTime expiryDate,
            int quantity,
            int reservedQuantity,
            InventoryLotStatus status
    ) {
    }
}
