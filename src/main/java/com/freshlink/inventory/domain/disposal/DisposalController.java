package com.freshlink.inventory.domain.disposal;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/owner/disposals")
public class DisposalController {

    private static final String STORE_ID_HEADER = "X-Store-Id";
    private static final String USER_ID_HEADER = "X-User-Id";

    private final DisposalService disposalService;

    public DisposalController(DisposalService disposalService) {
        this.disposalService = disposalService;
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
}
