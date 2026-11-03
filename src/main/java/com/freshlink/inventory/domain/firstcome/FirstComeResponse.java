package com.freshlink.inventory.domain.firstcome;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "선착순 상태. limit = 판매 한도, used = 선착순을 켠 뒤 잡혀 있거나 팔린 수량, "
        + "remaining = 지금 더 잡을 수 있는 수량 = min(limit − used, 구매 가능 수량), 0 아래로 가지 않는다")
public record FirstComeResponse(
        Long productId,
        int limit,
        int used,
        int remaining
) {
}
