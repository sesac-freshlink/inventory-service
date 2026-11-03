package com.freshlink.inventory.domain.memo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "점주 상품 메모 하나. 다음에 고치거나 지울 때 version을 그대로 보낸다")
public record MemoResponse(
        Long productId,
        String productCode,
        String name,
        String category,
        String content,
        Long version,
        OffsetDateTime updatedAt
) {
}
