package com.freshlink.inventory.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 아직 보내지 않은 결과 쪽지 수. Prometheus {@code inventory_outbox_unpublished}.
 * 0 근처에 머물러야 정상이다. 계속 늘면 outbox 발행기(SQS 전송)가 막힌 것이다.
 * Prometheus가 읽어 갈 때마다 세며, outbox 표의 (published, id) 인덱스를 탄다.
 */
@Component
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    static final String NAME = "inventory.outbox.unpublished";
    static final String COUNT_SQL = "SELECT COUNT(*) FROM outbox WHERE published = FALSE";

    private final JdbcTemplate jdbcTemplate;

    public OutboxMetrics(MeterRegistry registry, JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        Gauge.builder(NAME, this, OutboxMetrics::unpublished)
                .description("outbox에 남은 미발행 쪽지 수")
                .register(registry);
    }

    /** DB를 못 읽으면 NaN. 지표 수집이 앱을 멈추게 하지 않는다 */
    double unpublished() {
        try {
            Long count = jdbcTemplate.queryForObject(COUNT_SQL, Long.class);
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            log.warn("미발행 outbox 수를 읽지 못함: {}", e.getMessage());
            return Double.NaN;
        }
    }
}
