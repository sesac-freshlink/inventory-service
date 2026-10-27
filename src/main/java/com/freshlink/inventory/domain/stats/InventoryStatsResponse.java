package com.freshlink.inventory.domain.stats;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-42 판매·폐기 통계. from·to는 한국 날짜(둘 다 포함). "
        + "products는 기간 안에 변동이 있는 상품만, 폐기 많은 순")
public record InventoryStatsResponse(
        LocalDate from,
        LocalDate to,
        int inboundQuantity,
        int soldQuantity,
        int disposedQuantity,
        Double disposalRate,
        List<ProductStatsResponse> products
) {
}
