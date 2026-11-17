package com.freshlink.inventory.domain.clearance;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClearanceSettingTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 12, 0);

    @Test
    void 기본_판매_시간은_06시_이상_23시_미만이다() {
        ClearanceSetting s = ClearanceSetting.DEFAULT;
        assertThat(s.inSaleHours(NOW.withHour(6).withMinute(0))).isTrue();
        assertThat(s.inSaleHours(NOW.withHour(5).withMinute(59))).isFalse();
        assertThat(s.inSaleHours(NOW.withHour(22).withMinute(59))).isTrue();
        assertThat(s.inSaleHours(NOW.withHour(23).withMinute(0))).isFalse();
    }

    @Test
    void 끝_24시는_자정까지_판다() {
        ClearanceSetting s = new ClearanceSetting(true, 30, 0, 24);
        assertThat(s.inSaleHours(NOW.withHour(0).withMinute(0))).isTrue();
        assertThat(s.inSaleHours(NOW.withHour(23).withMinute(59))).isTrue();
    }

    @Test
    void 기본_단가는_30퍼센트_할인하고_10원_아래를_버린다() {
        ClearanceSetting s = ClearanceSetting.DEFAULT;
        assertThat(s.price(1500L)).isEqualTo(1050L);
        assertThat(s.price(1990L)).isEqualTo(1390L);
        assertThat(s.price(1234L)).isEqualTo(860L);
    }

    @Test
    void 할인율_90퍼센트도_10원_아래를_버린다() {
        ClearanceSetting s = new ClearanceSetting(true, 90, 6, 23);
        assertThat(s.price(1500L)).isEqualTo(150L);
        assertThat(s.price(2990L)).isEqualTo(290L);  // 299 → 290
    }

    @Test
    void 할인가가_10원보다_작으면_10원이고_정가를_넘지_않는다() {
        ClearanceSetting s = new ClearanceSetting(true, 90, 6, 23);
        assertThat(s.price(90L)).isEqualTo(10L);     // 9 → 0 → 10
        assertThat(s.price(5L)).isEqualTo(5L);
    }
}
