package com.freshlink.inventory.domain.stats;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;

/** gateway 라우트 /api/owner/inventory/** 아래라 gateway를 고치지 않아도 된다 */
@RestController
@RequestMapping("/owner/inventory/stats")
public class InventoryStatsController {

    private static final String STORE_ID_HEADER = "X-Store-Id";

    private final InventoryStatsService inventoryStatsService;

    public InventoryStatsController(InventoryStatsService inventoryStatsService) {
        this.inventoryStatsService = inventoryStatsService;
    }

    @Operation(summary = "S-42 상품별 입고·판매·폐기 수량과 폐기율 (from·to 한국 날짜. to를 빼면 오늘, from을 빼면 to까지 7일)")
    @GetMapping
    public ResponseEntity<InventoryStatsResponse> getStats(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(inventoryStatsService.getStats(storeId, from, to));
    }
}
