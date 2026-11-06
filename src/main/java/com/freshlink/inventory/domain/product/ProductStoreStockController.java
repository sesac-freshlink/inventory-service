package com.freshlink.inventory.domain.product;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 주변 점포 재고 (공개). gateway 공개 경로 GET /api/products/{productId}/stores */
@RestController
public class ProductStoreStockController {

    private final ProductStoreStockService productStoreStockService;

    public ProductStoreStockController(ProductStoreStockService productStoreStockService) {
        this.productStoreStockService = productStoreStockService;
    }

    @Operation(summary = "주변 점포 재고 (공개). 이 상품을 지금 살 수 있는 점포와 수량, 많이 남은 순. "
            + "없는 상품은 404 PRODUCT_NOT_FOUND, 어디에도 없으면 빈 배열")
    @GetMapping("/products/{productId}/stores")
    public ResponseEntity<List<StoreStockResponse>> getStores(@PathVariable Long productId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(productStoreStockService.getStoresWithStock(productId));
    }
}
