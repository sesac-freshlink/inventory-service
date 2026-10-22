package com.freshlink.inventory.domain.inventory;

import java.util.List;
import java.time.LocalDate;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/owner/inventory")
public class InventoryController {

    private static final String STORE_ID_HEADER = "X-Store-Id";

    private final InventoryQueryService inventoryQueryService;
    private final LedgerQueryService ledgerQueryService;

    public InventoryController(
            InventoryQueryService inventoryQueryService,
            LedgerQueryService ledgerQueryService
    ) {
        this.inventoryQueryService = inventoryQueryService;
        this.ledgerQueryService = ledgerQueryService;
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

    @GetMapping("/ledger")
    public ResponseEntity<List<LedgerResponse>> getLedger(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) LedgerType type,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ledgerQueryService.getLedger(
                        storeId,
                        productId,
                        type,
                        from,
                        to
                ));
    }
}