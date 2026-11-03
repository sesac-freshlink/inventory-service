package com.freshlink.inventory.common.messaging;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.DisposeMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.inventory.common.metrics.CommandMetrics;
import com.freshlink.inventory.domain.inventory.CommandResult;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 쪽지를 받을 때마다 inventory.command Timer에 종류·결과별로 한 번씩 남는지,
 * 실패하면 Loki에서 쪽지로 찾을 수 있는 로그 한 줄이 남는지 본다 (DB 없이)
 */
class InventoryCommandListenerTest {

    static final int MAX_RECEIVE_COUNT = 3;
    static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-10-25T12:00:00+09:00");

    SimpleMeterRegistry registry;
    InventoryCommandService service;
    InventoryCommandListener listener;
    Logger listenerLogger;
    ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = mock(InventoryCommandService.class);
        listener = new InventoryCommandListener(service, new CommandMetrics(registry), MAX_RECEIVE_COUNT);

        listenerLogger = (Logger) LoggerFactory.getLogger(InventoryCommandListener.class);
        logs = new ListAppender<>();
        logs.start();
        listenerLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        listenerLogger.detachAppender(logs);
    }

    @Test
    void 처리한_쪽지는_processed로_센다() {
        when(service.handle(any())).thenReturn(CommandResult.PROCESSED);

        listener.onCommand(reserveBody(), "1");
        listener.onCommand(reserveBody(), "1");

        assertThat(timer("RESERVE", "processed").count()).isEqualTo(2);
        assertThat(registry.find("inventory.command").tag("result", "duplicate").timer()).isNull();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 같은_messageId로_건너뛴_쪽지는_duplicate로_센다() {
        when(service.handle(any())).thenReturn(CommandResult.DUPLICATE);

        listener.onCommand(reserveBody(), "1");

        assertThat(timer("RESERVE", "duplicate").count()).isEqualTo(1);
    }

    @Test
    void 처리_중_예외는_failed로_세고_그대로_던져_SQS가_다시_보내게_한다() {
        when(service.handle(any())).thenThrow(new IllegalStateException("다른 점포의 lot"));

        assertThatThrownBy(() -> listener.onCommand(reserveBody(), "1"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(timer("RESERVE", "failed").count()).isEqualTo(1);
    }

    @Test
    void 쪽지로_읽지_못한_본문은_UNKNOWN_failed로_세고_처리하지_않는다() {
        assertThatThrownBy(() -> listener.onCommand("{\"type\":\"NOPE\"}", "1"))
                .isInstanceOf(RuntimeException.class);

        assertThat(timer("UNKNOWN", "failed").count()).isEqualTo(1);
        verify(service, never()).handle(any());
    }

    @Test
    void 실패하면_종류_messageId_점포_주문번호_받은_횟수_이유를_warn_한_줄로_남긴다() {
        ReserveMessage message = new ReserveMessage(newId(), CREATED_AT, 501L, 5L, 16L, 2);
        when(service.handle(any())).thenThrow(new IllegalStateException("다른 점포의 lot"));

        assertThatThrownBy(() -> listener.onCommand(MessageJson.toJson(message), "1"));

        assertThat(logs.list).hasSize(1);
        ILoggingEvent event = logs.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("쪽지 처리 실패")
                .contains("type=RESERVE")
                .contains("messageId=" + message.messageId())
                .contains("storeId=5")
                .contains("orderId=501")
                .contains("productId=16")
                .contains("quantity=2")
                .contains("receiveCount=1/3")
                .contains("IllegalStateException: 다른 점포의 lot")
                .doesNotContain("DLQ");
    }

    @Test
    void 마지막_시도에서_실패하면_DLQ로_간다고_error로_남긴다() {
        when(service.handle(any())).thenThrow(new IllegalStateException("DB 연결 끊김"));

        assertThatThrownBy(() -> listener.onCommand(reserveBody(), "3"));

        ILoggingEvent event = logs.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage())
                .contains("receiveCount=3/3")
                .contains("DLQ로 간다");
    }

    @Test
    void 종류마다_찾을_때_쓰는_번호를_남긴다() {
        when(service.handle(any())).thenThrow(new IllegalStateException("실패"));
        List<BaseMessage> messages = List.of(
                new InboundMessage(newId(), CREATED_AT, 5L, "SHP-9",
                        List.of(new InboundItem("P016", 3, CREATED_AT), new InboundItem("P012", 1, CREATED_AT))),
                new ConfirmMessage(newId(), CREATED_AT, 502L, 5L),
                new ReleaseMessage(newId(), CREATED_AT, 503L, 5L, ReleaseReason.EXPIRED),
                new DisposeMessage(newId(), CREATED_AT, 7L, 5L, 17L, 10, "owner")
        );

        messages.forEach(m -> assertThatThrownBy(() -> listener.onCommand(MessageJson.toJson(m), "2")));

        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).satisfiesExactly(
                inbound -> assertThat(inbound).contains("type=INBOUND", "shipmentId=SHP-9", "productCodes=[P016, P012]"),
                confirm -> assertThat(confirm).contains("type=CONFIRM", "orderId=502"),
                release -> assertThat(release).contains("type=RELEASE", "orderId=503", "reason=EXPIRED"),
                dispose -> assertThat(dispose).contains("type=DISPOSE", "disposalId=7", "lotId=17", "quantity=10")
        );
    }

    @Test
    void 읽지_못한_본문은_앞부분과_이유를_남긴다() {
        assertThatThrownBy(() -> listener.onCommand("{\"type\":\"NOPE\",\"orderId\":9}", "3"));

        ILoggingEvent event = logs.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage())
                .contains("쪽지 본문을 읽지 못함")
                .contains("receiveCount=3/3")
                .contains("DLQ로 간다")
                .contains("{\"type\":\"NOPE\",\"orderId\":9}");
    }

    @Test
    void 받은_횟수를_모르면_물음표로_남긴다() {
        when(service.handle(any())).thenThrow(new IllegalStateException("실패"));

        assertThatThrownBy(() -> listener.onCommand(reserveBody(), null));

        assertThat(logs.list.getFirst().getFormattedMessage()).contains("receiveCount=?/3");
        assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.WARN);
    }

    private Timer timer(String type, String result) {
        return registry.get("inventory.command").tag("type", type).tag("result", result).timer();
    }

    private String reserveBody() {
        return MessageJson.toJson(new ReserveMessage(newId(), CREATED_AT, 1L, 1L, 1L, 1));
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
