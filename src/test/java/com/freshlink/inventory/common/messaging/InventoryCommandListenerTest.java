package com.freshlink.inventory.common.messaging;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.inventory.common.metrics.CommandMetrics;
import com.freshlink.inventory.domain.inventory.CommandResult;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 쪽지를 받을 때마다 inventory.command Timer에 종류·결과별로 한 번씩 남는지 본다 (DB 없이) */
class InventoryCommandListenerTest {

    SimpleMeterRegistry registry;
    InventoryCommandService service;
    InventoryCommandListener listener;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = mock(InventoryCommandService.class);
        listener = new InventoryCommandListener(service, new CommandMetrics(registry));
    }

    @Test
    void 처리한_쪽지는_processed로_센다() {
        when(service.handle(any())).thenReturn(CommandResult.PROCESSED);

        listener.onCommand(reserveBody());
        listener.onCommand(reserveBody());

        assertThat(timer("RESERVE", "processed").count()).isEqualTo(2);
        assertThat(registry.find("inventory.command").tag("result", "duplicate").timer()).isNull();
    }

    @Test
    void 같은_messageId로_건너뛴_쪽지는_duplicate로_센다() {
        when(service.handle(any())).thenReturn(CommandResult.DUPLICATE);

        listener.onCommand(reserveBody());

        assertThat(timer("RESERVE", "duplicate").count()).isEqualTo(1);
    }

    @Test
    void 처리_중_예외는_failed로_세고_그대로_던져_SQS가_다시_보내게_한다() {
        when(service.handle(any())).thenThrow(new IllegalStateException("다른 점포의 lot"));

        assertThatThrownBy(() -> listener.onCommand(reserveBody()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(timer("RESERVE", "failed").count()).isEqualTo(1);
    }

    @Test
    void 쪽지로_읽지_못한_본문은_UNKNOWN_failed로_세고_처리하지_않는다() {
        assertThatThrownBy(() -> listener.onCommand("{\"type\":\"NOPE\"}"))
                .isInstanceOf(RuntimeException.class);

        assertThat(timer("UNKNOWN", "failed").count()).isEqualTo(1);
        verify(service, never()).handle(any());
    }

    private Timer timer(String type, String result) {
        return registry.get("inventory.command").tag("type", type).tag("result", result).timer();
    }

    private String reserveBody() {
        return MessageJson.toJson(new ReserveMessage(
                UUID.randomUUID().toString(),
                OffsetDateTime.parse("2026-10-25T12:00:00+09:00"),
                1L,
                1L,
                1L,
                1
        ));
    }
}
