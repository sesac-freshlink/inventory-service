package com.freshlink.inventory.domain.stats;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/owner/inventory/sales-ranking")
public class SalesRankingController {

    private static final String STORE_ID_HEADER = "X-Store-Id";

    private final SalesRankingService salesRankingService;

    public SalesRankingController(SalesRankingService salesRankingService) {
        this.salesRankingService = salesRankingService;
    }

    @Operation(summary = "상품별 판매 순위 (결제 확정 수량 기준. from·to 한국 날짜, 기본 오늘까지 7일. limit 1~100, 기본 10)")
    @GetMapping
    public ResponseEntity<SalesRankingResponse> getRanking(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false) Integer limit
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(salesRankingService.getRanking(storeId, from, to, limit));
    }
}
