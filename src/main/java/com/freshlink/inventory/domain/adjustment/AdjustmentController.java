package com.freshlink.inventory.domain.adjustment;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;

/** gateway 라우트 /api/owner/inventory/** 아래라 gateway를 고치지 않아도 된다 */
@RestController
@RequestMapping("/owner/inventory/adjustments")
public class AdjustmentController {

    private static final String STORE_ID_HEADER = "X-Store-Id";
    private static final String USER_ID_HEADER = "X-User-Id";

    private final AdjustmentService adjustmentService;

    public AdjustmentController(AdjustmentService adjustmentService) {
        this.adjustmentService = adjustmentService;
    }

    /** 폐기(202)와 달리 바로 고치므로 201 */
    @Operation(summary = "S-14 lot 수동 조정·실사 (201. 예약보다 적게 줄이면 409 BELOW_RESERVED, 쪽지 처리와 겹치면 409 LOT_CHANGED)")
    @PostMapping
    public ResponseEntity<AdjustmentResponse> adjust(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @RequestHeader(USER_ID_HEADER) String userId,
            @Valid @RequestBody AdjustmentRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adjustmentService.adjust(storeId, userId, request));
    }

    @Operation(summary = "S-14 조정 이력 (최신순 최근 100건)")
    @GetMapping
    public ResponseEntity<List<AdjustmentHistoryResponse>> getAdjustments(
            @RequestHeader(STORE_ID_HEADER) Long storeId
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(adjustmentService.getAdjustments(storeId));
    }
}
