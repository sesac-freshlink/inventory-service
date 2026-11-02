package com.freshlink.inventory.domain.disposal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;

/**
 * 경과 lot 한 번에 폐기. gateway가 /api/owner/disposals는 그 경로 하나만 넘기므로,
 * gateway를 고치지 않으려고 /api/owner/inventory/** 아래에 둔다.
 */
@RestController
public class ExpiredDisposalController {

    private static final String STORE_ID_HEADER = "X-Store-Id";
    private static final String USER_ID_HEADER = "X-User-Id";

    private final DisposalService disposalService;

    public ExpiredDisposalController(DisposalService disposalService) {
        this.disposalService = disposalService;
    }

    /** 단건 폐기처럼 재고는 DISPOSE 쪽지를 처리할 때 바뀌므로 202로 응답한다 */
    @Operation(summary = "경과 lot 한 번에 폐기 요청 (202 접수. 기한이 지난 lot마다 폐기 가능 수량 전부, 처리 전 요청 수량은 뺀다)")
    @PostMapping("/owner/inventory/disposals/expired")
    public ResponseEntity<ExpiredDisposalResponse> requestExpiredDisposal(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @RequestHeader(USER_ID_HEADER) String userId
    ) {
        return ResponseEntity.accepted().body(disposalService.requestExpired(storeId, userId));
    }
}
