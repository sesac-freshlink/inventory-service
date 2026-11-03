package com.freshlink.inventory.domain.disposal;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "경과 lot 한 번에 폐기 접수 결과(202). lotCount는 요청한 lot 수, quantity는 요청한 수량 합. "
        + "items는 lot마다의 접수 결과(단건 폐기 응답과 같다). 버릴 lot이 없으면 0과 빈 목록")
public record ExpiredDisposalResponse(
        int lotCount,
        int quantity,
        List<DisposalResponse> items
) {
}
