package com.freshlink.inventory.domain.inventory;

import java.util.List;
import java.util.Set;
import java.time.LocalDate;
import java.time.YearMonth;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;

import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/owner/inventory")
public class InventoryController {

    private static final String STORE_ID_HEADER = "X-Store-Id";

    private final InventoryQueryService inventoryQueryService;
    private final LedgerQueryService ledgerQueryService;
    private final ExpiryCalendarService expiryCalendarService;

    public InventoryController(
            InventoryQueryService inventoryQueryService,
            LedgerQueryService ledgerQueryService,
            ExpiryCalendarService expiryCalendarService
    ) {
        this.inventoryQueryService = inventoryQueryService;
        this.ledgerQueryService = ledgerQueryService;
        this.expiryCalendarService = expiryCalendarService;
    }

    @Operation(summary = "유통기한 달력 (month = yyyy-MM, 빼면 이번 달). 그 달에 끝나는 lot을 날짜별로 묶고 lot 목록까지 준다")
    @GetMapping("/expiry-calendar")
    public ResponseEntity<ExpiryCalendarResponse> getExpiryCalendar(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(expiryCalendarService.getCalendar(storeId, month));
    }

    @Operation(summary = "S-10 점포 재고 목록 한 페이지 (점주 화면이 3초마다 조회). "
            + "q = 상품명·상품 코드 검색, status = IMMINENT·EXPIRED·SOLD_OUT·LOW_STOCK 쉼표로 여러 개(모두 만족), "
            + "memo = true면 메모 있는 상품만, page 0부터, size 1~100(기본 20)")
    @GetMapping
    public ResponseEntity<InventoryPageResponse> getInventory(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Set<InventoryStatusFilter> status,
            @RequestParam(defaultValue = "false") boolean memo,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(inventoryQueryService.searchInventory(
                        storeId, new InventorySearchCondition(q, status, memo), page, size));
    }

    @Operation(summary = "S-11 상품 하나의 lot 목록 (유통기한 빠른 순)")
    @GetMapping("/{productId}/lots")
    public ResponseEntity<List<InventoryLotResponse>> getLots(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @PathVariable Long productId
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(inventoryQueryService.getLots(storeId, productId));
    }

    @Operation(summary = "S-13 재고 변동 이력 (최신순 최근 100건, 선택 필터 type·productId·from·to)")
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

    @Operation(summary = "S-30 임박(24시간 이내)·경과 lot 목록")
    @GetMapping("/expiring")
    public ResponseEntity<List<ExpiringInventoryResponse>> getExpiring(
            @RequestHeader(STORE_ID_HEADER) Long storeId
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(inventoryQueryService.getExpiring(storeId));
    }
}