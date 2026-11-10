package com.freshlink.inventory.domain.product;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주변 점포 재고 한 줄. 그 점포에서 지금 살 수 있는 수량(1 이상)과 먼저 받게 될 상품의 유통기한. "
        + "점포 이름·주소·위치는 member 점포 API에서 읽는다")
public record StoreStockResponse(
        Long storeId,
        int availableQuantity,
        OffsetDateTime nearestExpiry
) {
}
