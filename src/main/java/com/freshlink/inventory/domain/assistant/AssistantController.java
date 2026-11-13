package com.freshlink.inventory.domain.assistant;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 점주 비서 (.github docs/기능이해/AI-점주-비서.md). gateway가 /api/owner/inventory/**를 이미 inventory로 보내서
 * 경로를 그 아래에 둔다. 점포는 X-Store-Id(없으면 필터가 403 STORE_NOT_LINKED)
 */
@RestController
public class AssistantController {

    private static final String STORE_ID_HEADER = "X-Store-Id";

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @Operation(summary = "AI 점주 비서 질문 (점주). presetId(Q1~Q5) 또는 question(200자까지) 하나를 보낸다. "
            + "답은 {answer, source: AI|RULE, generatedAt}. AI를 부를 수 없으면 source RULE(버튼은 규칙 기반 목록, 직접 입력은 안내 문구). "
            + "꺼져 있으면 503 ASSISTANT_DISABLED, 동시 호출 초과 429 ASSISTANT_BUSY, 점포당 하루 50번 초과 429 ASSISTANT_RATE_LIMITED, "
            + "본문이 틀리면 400 VALIDATION_ERROR. 늦어도 25초 안에 답한다")
    @PostMapping("/owner/inventory/assistant/ask")
    public ResponseEntity<AssistantAnswer> ask(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @Valid @RequestBody AskRequest request
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(assistantService.ask(storeId, request));
    }
}
