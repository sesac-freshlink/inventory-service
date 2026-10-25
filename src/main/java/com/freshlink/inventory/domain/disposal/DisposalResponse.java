package com.freshlink.inventory.domain.disposal;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-32 폐기 접수 결과(202). 재고는 DISPOSE 쪽지를 처리할 때 줄어든다")
public record DisposalResponse(
        Long disposalId,
        Long lotId,
        int quantity
) {
}
