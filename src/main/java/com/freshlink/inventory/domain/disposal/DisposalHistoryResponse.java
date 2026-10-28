package com.freshlink.inventory.domain.disposal;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-33 폐기 요청 한 줄. status가 REQUESTED면 DISPOSE 쪽지 처리 전, DONE이면 lot 수량이 줄었고, SKIPPED면 처리 때 폐기 가능 수량이 모자라 재고가 그대로다")
public record DisposalHistoryResponse(
        Long disposalId,
        Long lotId,
        Long productId,
        String productName,
        OffsetDateTime expiryDate,
        int quantity,
        DisposalStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime processedAt
) {
}
