package com.freshlink.inventory.domain.disposal;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "S-32 폐기 접수 결과(202). 재고는 DISPOSE 쪽지 처리가 DONE으로 끝날 때 줄고, 그 사이 예약으로 폐기 가능 수량이 모자라면 SKIPPED로 끝나 줄지 않는다")
public record DisposalResponse(
        Long disposalId,
        Long lotId,
        int quantity
) {
}
