package com.freshlink.inventory.common.metrics;

import java.time.Duration;
import java.util.function.Supplier;

import com.freshlink.inventory.domain.inventory.CommandResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;

/**
 * 쪽지 처리 지표. 쪽지 한 번 받을 때마다 걸린 시간을 종류(type)·결과(result)별 Timer 하나에 남긴다.
 * Prometheus에서는 {@code inventory_command_seconds_count}(건수), {@code _sum}(시간 합), {@code _bucket}(분포)로 보인다.
 *
 * <ul>
 *   <li>result=processed: 처음 받은 쪽지를 처리했다</li>
 *   <li>result=duplicate: 같은 messageId라 건너뛰었다 (SQS가 같은 쪽지를 다시 보낸 경우)</li>
 *   <li>result=failed: 예외로 트랜잭션이 되돌려졌다. SQS가 다시 보내므로 시도마다 하나씩 센다</li>
 * </ul>
 * 본문을 쪽지로 읽지 못하면 type=UNKNOWN, result=failed로 센다.
 */
@Component
public class CommandMetrics {

    static final String NAME = "inventory.command";
    static final String UNKNOWN_TYPE = "UNKNOWN";

    private final MeterRegistry registry;

    public CommandMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** work를 실행하고 걸린 시간을 남긴다. 예외는 failed로 남기고 그대로 던진다 */
    public CommandResult record(String type, Supplier<CommandResult> work) {
        Timer.Sample sample = Timer.start(registry);
        String result = "failed";
        try {
            CommandResult outcome = work.get();
            result = outcome == CommandResult.DUPLICATE ? "duplicate" : "processed";
            return outcome;
        } finally {
            sample.stop(timer(type, result));
        }
    }

    /** 본문을 쪽지로 읽지 못했다. 처리하지 않았으니 시간은 0으로 센다 */
    public void recordUnreadable() {
        timer(UNKNOWN_TYPE, "failed").record(Duration.ZERO);
    }

    private Timer timer(String type, String result) {
        return Timer.builder(NAME)
                .description("inventory-commands.fifo 쪽지 한 번 처리에 걸린 시간")
                .tag("type", type)
                .tag("result", result)
                // Grafana에서 histogram_quantile로 p95를 보려고 구간(bucket)을 낸다. 범위를 좁혀 구간 수를 줄인다
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(registry);
    }
}
