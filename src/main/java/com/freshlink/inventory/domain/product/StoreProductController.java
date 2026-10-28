package com.freshlink.inventory.domain.product;

import java.util.List;

import com.freshlink.inventory.domain.inventory.InventoryQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/stores/{storeId}/products")
public class StoreProductController {

    private final InventoryQueryService inventoryQueryService;

    public StoreProductController(
            InventoryQueryService inventoryQueryService
    ) {
        this.inventoryQueryService = inventoryQueryService;
    }

    @Operation(summary = "U-10 고객용 점포 상품 목록 (공개)")
    @GetMapping
    public ResponseEntity<List<StoreProductResponse>> getProducts(
            @PathVariable Long storeId
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(inventoryQueryService.getStoreProducts(storeId));
    }
}