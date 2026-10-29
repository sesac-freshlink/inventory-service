package com.freshlink.inventory.domain.product;

import java.util.List;

import com.freshlink.inventory.domain.inventory.InventoryQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/stores/{storeId}/products")
public class StoreProductController {

    private final InventoryQueryService inventoryQueryService;

    public StoreProductController(
            InventoryQueryService inventoryQueryService
    ) {
        this.inventoryQueryService = inventoryQueryService;
    }

    /** 검색어 최대 길이. 점포 검색(member ST-01)과 같다 */
    static final int MAX_QUERY_LENGTH = 50;

    @Operation(summary = "U-10 고객용 점포 상품 목록 (공개). q = 상품명 검색(선택, 50자까지), 상품명 순")
    @GetMapping
    public ResponseEntity<List<StoreProductResponse>> getProducts(
            @PathVariable Long storeId,
            @RequestParam(required = false) @Size(max = MAX_QUERY_LENGTH) String q
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(inventoryQueryService.getStoreProducts(storeId, q));
    }
}