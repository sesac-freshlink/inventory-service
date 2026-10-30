package com.freshlink.inventory.domain.stats;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "상품별 판매 순위. from·to는 한국 날짜(둘 다 포함). "
        + "판매는 결제가 확정되어 실물이 줄어든 수량(원장 SALE)에서 환불 복원(RETURN)을 뺀 순판매. totalSoldQuantity는 limit으로 자르기 전 기간 전체 판매")
public record SalesRankingResponse(
        LocalDate from,
        LocalDate to,
        int totalSoldQuantity,
        List<SalesRankItem> items
) {
}
