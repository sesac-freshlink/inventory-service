package com.freshlink.inventory.domain.inventory;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/owner/inventory")
public class InventoryController {

    private static final String STORE_ID_HEADER = "X-Store-Id";

    private final InventoryQueryService inventoryQueryService;

    public InventoryController(InventoryQueryService inventoryQueryService) {
        this.inventoryQueryService = inventoryQueryService;
    }

    @GetMapping
    public ResponseEntity<List<InventorySummaryResponse>> getInventory(
            @RequestHeader(STORE_ID_HEADER) Long storeId
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(inventoryQueryService.getInventory(storeId));
    }

    @GetMapping("/{productId}/lots")
    public ResponseEntity<List<InventoryLotResponse>> getLots(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @PathVariable Long productId
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(inventoryQueryService.getLots(storeId, productId));
    }
}