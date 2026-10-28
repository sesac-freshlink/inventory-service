package com.freshlink.inventory.domain.safetystock;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;

/** gateway 라우트 /api/owner/inventory/** 아래라 gateway를 고치지 않아도 된다 */
@RestController
@RequestMapping("/owner/inventory/safety-stocks")
public class SafetyStockController {

    private static final String STORE_ID_HEADER = "X-Store-Id";

    private final SafetyStockService safetyStockService;

    public SafetyStockController(SafetyStockService safetyStockService) {
        this.safetyStockService = safetyStockService;
    }

    @Operation(summary = "S-40 상품별 안전재고 목록 (상품 전체, 정하지 않은 상품은 기본값 5)")
    @GetMapping
    public ResponseEntity<List<SafetyStockResponse>> getSafetyStocks(
            @RequestHeader(STORE_ID_HEADER) Long storeId
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(safetyStockService.getSafetyStocks(storeId));
    }

    @Operation(summary = "S-40 상품 하나의 안전재고 변경 (0~999. 같은 값으로 다시 보내도 결과가 같다)")
    @PutMapping("/{productId}")
    public SafetyStockResponse update(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @PathVariable Long productId,
            @Valid @RequestBody SafetyStockRequest request
    ) {
        return safetyStockService.update(storeId, productId, request.safetyStock());
    }
}
