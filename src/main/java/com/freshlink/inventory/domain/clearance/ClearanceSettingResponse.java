package com.freshlink.inventory.domain.clearance;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "분류 하나의 마감세일 설정. customized가 false면 점주가 정하지 않아 기본값(적용, 30%, 06~23시)이다. "
        + "판매 시간은 saleStartHour시 정각부터 saleEndHour시 정각 전까지(24 = 자정)")
public record ClearanceSettingResponse(
        String category,
        boolean enabled,
        int discountPercent,
        int saleStartHour,
        int saleEndHour,
        boolean customized
) {
    static ClearanceSettingResponse of(String category, ClearanceSetting setting, boolean customized) {
        return new ClearanceSettingResponse(category, setting.enabled(), setting.discountPercent(),
                setting.saleStartHour(), setting.saleEndHour(), customized);
    }
}
