package com.freshlink.inventory.domain.product;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "U-10 고객용 점포 상품 한 줄. availableQuantity 0이면 soldOut. imageUrl은 없으면 null")
public record StoreProductResponse(
        Long productId,
        String name,
        String category,
        long price,
        String imageUrl,
        int availableQuantity,
        boolean soldOut
) {
}