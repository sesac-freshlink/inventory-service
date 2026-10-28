package com.freshlink.inventory.domain.adjustment;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-14 조정 결과. 쪽지를 거치지 않으므로 응답 때 이미 lot 수량이 바뀌어 있다")
public record AdjustmentResponse(
        Long adjustmentId,
        Long lotId,
        int delta,
        int quantityBefore,
        int quantityAfter
) {
}
