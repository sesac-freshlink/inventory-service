package com.freshlink.inventory.domain.firstcome;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 선착순 판매 한도.
 * <ul>
 *   <li>점주 설정: /owner/inventory/first-come — gateway 라우트 /api/owner/inventory/** 아래</li>
 *   <li>남은 수량: /stores/{storeId}/products/{productId}/first-come — order 대기열이 클러스터 안에서
 *       http://inventory-service:8080 으로 부른다. gateway 공개 라우트에는 없다(밖에서는 못 부른다)</li>
 * </ul>
 */
@RestController
public class FirstComeController {

    private static final String STORE_ID_HEADER = "X-Store-Id";

    private final FirstComeService firstComeService;

    public FirstComeController(FirstComeService firstComeService) {
        this.firstComeService = firstComeService;
    }

    @Operation(summary = "선착순 남은 수량 (order 대기열용, 클러스터 안). 선착순이 아니면 404 FIRST_COME_NOT_FOUND")
    @GetMapping("/stores/{storeId}/products/{productId}/first-come")
    public ResponseEntity<FirstComeResponse> getStatus(
            @PathVariable Long storeId,
            @PathVariable Long productId
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(firstComeService.getStatus(storeId, productId));
    }

    @Operation(summary = "점주 선착순 설정 목록 (상품 전체, 이름순)")
    @GetMapping("/owner/inventory/first-come")
    public ResponseEntity<List<FirstComeSettingResponse>> getSettings(
            @RequestHeader(STORE_ID_HEADER) Long storeId
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(firstComeService.getSettings(storeId));
    }

    @Operation(summary = "선착순 켜기·한도 바꾸기 (1~999). 이미 선착순이면 한도만 바뀌고 센 수량은 이어진다")
    @PutMapping("/owner/inventory/first-come/{productId}")
    public FirstComeSettingResponse enable(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @PathVariable Long productId,
            @Valid @RequestBody FirstComeRequest request
    ) {
        return firstComeService.enable(storeId, productId, request.limit());
    }

    @Operation(summary = "선착순 끄기 (선착순이 아니어도 결과가 같다)")
    @DeleteMapping("/owner/inventory/first-come/{productId}")
    public FirstComeSettingResponse disable(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @PathVariable Long productId
    ) {
        return firstComeService.disable(storeId, productId);
    }
}
