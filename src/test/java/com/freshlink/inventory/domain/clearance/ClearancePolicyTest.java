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
}
