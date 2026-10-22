package com.freshlink.inventory.domain.product;

public record StoreProductResponse(
        Long productId,
        String name,
        String category,
        long price,
        int availableQuantity,
        boolean soldOut
) {
}