package com.freshlink.inventory.domain.disposal;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/owner/disposals")
public class DisposalController {

    private static final String STORE_ID_HEADER = "X-Store-Id";
    private static final String USER_ID_HEADER = "X-User-Id";

    private final DisposalService disposalService;
    private final DisposalQueryService disposalQueryService;

    public DisposalController(
            DisposalService disposalService,
            DisposalQueryService disposalQueryService
    ) {
        this.disposalService = disposalService;
        this.disposalQueryService = disposalQueryService;
    }

    /** 재고는 DISPOSE 쪽지를 처리할 때 바뀌므로 202로 응답한다 */
    @Operation(summary = "S-32 lot 폐기 요청 (202 접수. 재고는 DISPOSE 쪽지 처리가 DONE으로 끝날 때만 감소, 모자라면 SKIPPED)")
    @PostMapping
    public ResponseEntity<DisposalResponse> requestDisposal(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @RequestHeader(USER_ID_HEADER) String userId,
            @Valid @RequestBody DisposalRequest request
    ) {
        return ResponseEntity.accepted()
                .body(disposalService.request(storeId, userId, request));
    }

    /** 화면이 폴링하므로 캐시하지 않는다. 같은 경로(/owner/disposals)라 gateway 라우트가 그대로 쓰인다 */
    @Operation(summary = "S-33 폐기 요청 이력 (최신순 최근 100건, 선택 필터 status=REQUESTED|DONE|SKIPPED)")
    @GetMapping
    public ResponseEntity<List<DisposalHistoryResponse>> getDisposals(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @RequestParam(required = false) DisposalStatus status
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(disposalQueryService.getDisposals(storeId, status));
    }
}
