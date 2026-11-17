package com.freshlink.inventory.domain.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AssistantServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 2026-11-20 14:05 KST */
    private static final Instant NOW = Instant.parse("2026-11-20T05:05:00Z");

    private final AssistantModel model = mock(AssistantModel.class);
    private final StoreSummaryService summaryService = mock(StoreSummaryService.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final StoreSummary summary = new StoreSummary(
            new StoreSummary.Store(1L, OffsetDateTime.ofInstant(NOW, SEOUL)),
            List.of(new StoreSummary.ProductSummary(12L, "매운 컵라면", "라면", 1500L, 3, 5, 118, 140,
                    List.of(), 150, 0, Map.of("11-14", 18))),
            List.of(new StoreSummary.LotSummary(44L, "닭가슴살 샐러드", 4,
                    OffsetDateTime.parse("2026-11-20T23:59:00+09:00"), "IMMINENT")));

    private AssistantService service(boolean enabled, String modelId) {
        when(summaryService.build(1L)).thenReturn(summary);
        return new AssistantService(new AssistantProperties(enabled, modelId), model, summaryService,
                JsonMapper.builder().build(), meterRegistry, clock);
    }

    private AssistantService service() {
        return service(true, "global.anthropic.claude-sonnet-5-5");
    }

    @Test
    void 꺼져_있으면_ASSISTANT_DISABLED이고_AI를_부르지_않는다() {
        assertThatThrownBy(() -> service(false, "m").ask(1L, new AskRequest("Q1", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.ASSISTANT_DISABLED));
        verify(model, never()).ask(anyString(), anyString(), any());
    }

    @Test
    void 정상이면_AI_답과_질문_문장을_보내고_source는_AI다() {
        when(model.ask(anyString(), anyString(), eq(AssistantService.FIRST_CALL_TIMEOUT)))
                .thenReturn(new ModelAnswer("샐러드 4개가 오늘 끝나요.", 900, 40));

        AssistantAnswer answer = service().ask(1L, new AskRequest("Q1", null));

        assertThat(answer.answer()).isEqualTo("샐러드 4개가 오늘 끝나요.");
        assertThat(answer.source()).isEqualTo(AssistantAnswer.AI);
        assertThat(answer.generatedAt()).isEqualTo(OffsetDateTime.parse("2026-11-20T14:05:00+09:00"));
        verify(model).ask(eq(AssistantPrompt.SYSTEM),
                org.mockito.ArgumentMatchers.contains("질문: " + AssistantPreset.Q1.question()),
                eq(AssistantService.FIRST_CALL_TIMEOUT));
        assertThat(meterRegistry.counter("assistant.tokens", "kind", "input").count()).isEqualTo(900);
    }

    @Test
    void 시간_초과면_재시도_없이_규칙_답이다() {
        when(model.ask(anyString(), anyString(), any()))
                .thenThrow(new AssistantModelException("timeout", true, null));

        AssistantAnswer answer = service().ask(1L, new AskRequest("Q1", null));

        assertThat(answer.source()).isEqualTo(AssistantAnswer.RULE);
        assertThat(answer.answer()).contains("닭가슴살 샐러드 4개");
        verify(model, times(1)).ask(anyString(), anyString(), any());
    }

    @Test
    void 빠른_실패면_남은_시간으로_한_번_더_부르고_성공하면_AI_답이다() {
        when(model.ask(anyString(), anyString(), any()))
                .thenThrow(new AssistantModelException("throttled", false, null))
                .thenReturn(new ModelAnswer("다시 물어 받은 답", 10, 2));

        AssistantAnswer answer = service().ask(1L, new AskRequest(null, "오늘 뭐 챙겨야 해?"));

        assertThat(answer.source()).isEqualTo(AssistantAnswer.AI);
        verify(model, times(2)).ask(anyString(), anyString(), any());
    }

    @Test
    void 재시도도_실패하면_자유_질문은_안내_문구다() {
        when(model.ask(anyString(), anyString(), any()))
                .thenThrow(new AssistantModelException("throttled", false, null));

        AssistantAnswer answer = service().ask(1L, new AskRequest(null, "오늘 뭐 챙겨야 해?"));

        assertThat(answer).extracting(AssistantAnswer::source, AssistantAnswer::answer)
                .containsExactly(AssistantAnswer.RULE, RuleAnswers.FREE_QUESTION);
        verify(model, times(2)).ask(anyString(), anyString(), any());
    }

    @Test
    void 모델_ID가_없으면_AI를_부르지_않고_규칙_답이다() {
        AssistantAnswer answer = service(true, "").ask(1L, new AskRequest("Q3", null));

        assertThat(answer.source()).isEqualTo(AssistantAnswer.RULE);
        assertThat(answer.answer()).contains("매운 컵라면(남은 3개, 안전재고 5개)");
        verify(model, never()).ask(anyString(), anyString(), any());
    }

    @Test
    void 같은_버튼은_10분_동안_AI를_한_번만_부르고_자유_질문은_매번_부른다() {
        when(model.ask(anyString(), anyString(), any())).thenReturn(new ModelAnswer("답", 1, 1));
        AssistantService service = service();

        service.ask(1L, new AskRequest("Q2", null));
        service.ask(1L, new AskRequest("Q2", null));
        verify(model, times(1)).ask(anyString(), anyString(), any());

        service.ask(1L, new AskRequest(null, "라면 몇 개 남았어?"));
        service.ask(1L, new AskRequest(null, "라면 몇 개 남았어?"));
        verify(model, times(3)).ask(anyString(), anyString(), any());

        clock.plus(Duration.ofMinutes(11));
        service.ask(1L, new AskRequest("Q2", null));
        verify(model, times(4)).ask(anyString(), anyString(), any());
    }

    @Test
    void 점포당_하루_50번을_넘으면_ASSISTANT_RATE_LIMITED이고_다음_날_다시_된다() {
        when(model.ask(anyString(), anyString(), any())).thenReturn(new ModelAnswer("답", 1, 1));
        AssistantService service = service();
        for (int i = 0; i < AssistantService.DAILY_LIMIT_PER_STORE; i++) {
            service.ask(1L, new AskRequest(null, "질문 " + i));
        }

        assertThatThrownBy(() -> service.ask(1L, new AskRequest(null, "51번째")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.ASSISTANT_RATE_LIMITED));

        clock.plus(Duration.ofDays(1));
        assertThat(service.ask(1L, new AskRequest(null, "다음 날")).source()).isEqualTo(AssistantAnswer.AI);
    }

    @Test
    void 동시에_5개가_처리_중이면_6번째는_ASSISTANT_BUSY다() throws Exception {
        CountDownLatch inside = new CountDownLatch(AssistantService.MAX_CONCURRENT);
        CountDownLatch release = new CountDownLatch(1);
        when(model.ask(anyString(), anyString(), any())).thenAnswer(invocation -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new ModelAnswer("답", 1, 1);
        });
        AssistantService service = service();
        ExecutorService pool = Executors.newFixedThreadPool(AssistantService.MAX_CONCURRENT);
        try {
            for (int i = 0; i < AssistantService.MAX_CONCURRENT; i++) {
                int n = i;
                pool.submit(() -> service.ask(1L, new AskRequest(null, "느린 질문 " + n)));
            }
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.ask(1L, new AskRequest(null, "6번째")))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.ASSISTANT_BUSY));
        } finally {
            release.countDown();
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /** 테스트에서 시각을 옮길 수 있는 시계 */
    static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void plus(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return SEOUL;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
