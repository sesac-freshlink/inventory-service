package com.freshlink.inventory.domain.clearance;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;

/** gateway 라우트 /api/owner/inventory/** 아래라 gateway를 고치지 않아도 된다 */
@RestController
@RequestMapping("/owner/inventory/clearance-settings")
public class ClearanceSettingController {

    private static final String STORE_ID_HEADER = "X-Store-Id";

    private final ClearanceSettingService clearanceSettingService;

    public ClearanceSettingController(ClearanceSettingService clearanceSettingService) {
        this.clearanceSettingService = clearanceSettingService;
    }

    @Operation(summary = "분류별 마감세일 설정 목록 (상품 전체 분류, 가나다순. 정하지 않은 분류는 기본값)")
    @GetMapping
    public ResponseEntity<List<ClearanceSettingResponse>> getSettings(
            @RequestHeader(STORE_ID_HEADER) Long storeId
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(clearanceSettingService.getSettings(storeId));
    }

    @Operation(summary = "분류 하나의 마감세일 설정 저장 (할인율 1~95, 판매 시간 시작 0~23·끝 1~24, 시작 < 끝. 같은 값으로 다시 보내도 결과가 같다)")
    @PutMapping
    public ClearanceSettingResponse update(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @Valid @RequestBody ClearanceSettingRequest request
    ) {
        return clearanceSettingService.update(storeId, request);
    }
}
