package com.freshlink.inventory.domain.firstcome;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "점주 선착순 설정 한 줄. 상품 전체가 나오고, firstCome이 false면 limit·used·remaining은 null")
public record FirstComeSettingResponse(
        Long productId,
        String productCode,
        String name,
        String category,
        boolean firstCome,
        Integer limit,
        Integer used,
        Integer remaining
) {
}
