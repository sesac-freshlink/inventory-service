package com.freshlink.inventory.domain.clearance;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClearancePolicyTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 12, 0);

    @Test
    void 지금보다_뒤이고_24시간_안이면_마감세일_lot이다() {
        assertThat(ClearancePolicy.isClearanceLot(NOW.plusHours(1), NOW)).isTrue();
        // 경계: 정확히 24시간 뒤는 마감세일, 지금과 같으면 이미 지난 lot이다
        assertThat(ClearancePolicy.isClearanceLot(NOW.plusHours(24), NOW)).isTrue();
        assertThat(ClearancePolicy.isClearanceLot(NOW.plusHours(24).plusSeconds(1), NOW)).isFalse();
        assertThat(ClearancePolicy.isClearanceLot(NOW, NOW)).isFalse();
    }

    @Test
    void 판매_시간은_06시_이상_23시_미만이다() {
        assertThat(ClearancePolicy.inSaleHours(NOW.withHour(6).withMinute(0))).isTrue();
        assertThat(ClearancePolicy.inSaleHours(NOW.withHour(5).withMinute(59))).isFalse();
        assertThat(ClearancePolicy.inSaleHours(NOW.withHour(22).withMinute(59))).isTrue();
        assertThat(ClearancePolicy.inSaleHours(NOW.withHour(23).withMinute(0))).isFalse();
    }

    @Test
    void 단가는_30퍼센트_할인하고_10원_아래를_버린다() {
        assertThat(ClearancePolicy.price(1500L)).isEqualTo(1050L);
        assertThat(ClearancePolicy.price(1990L)).isEqualTo(1390L);
        assertThat(ClearancePolicy.price(1234L)).isEqualTo(860L);
    }
}
