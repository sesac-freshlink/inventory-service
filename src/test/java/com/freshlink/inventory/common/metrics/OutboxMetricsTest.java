package com.freshlink.inventory.common.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxMetricsTest {

    @Test
    void 미발행_outbox_수를_읽을_때마다_센다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(OutboxMetrics.COUNT_SQL, Long.class)).thenReturn(3L, 0L);
        new OutboxMetrics(registry, jdbcTemplate);

        assertThat(registry.get("inventory.outbox.unpublished").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("inventory.outbox.unpublished").gauge().value()).isZero();
    }

    @Test
    void DB를_못_읽으면_NaN이고_예외를_던지지_않는다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(OutboxMetrics.COUNT_SQL, Long.class))
                .thenThrow(new DataAccessResourceFailureException("DB 연결 끊김"));
        new OutboxMetrics(registry, jdbcTemplate);

        assertThat(registry.get("inventory.outbox.unpublished").gauge().value()).isNaN();
    }
}
