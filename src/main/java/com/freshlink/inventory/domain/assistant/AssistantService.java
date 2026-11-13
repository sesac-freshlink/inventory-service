package com.freshlink.inventory.domain.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 점주 비서 질문 하나를 처리한다 (문서 3-3, 4장).
 * <ul>
 *   <li>시간: 질문 하나 전체 25초 예산. CloudFront가 30초에 끊으므로 그 안에서 끝낸다. 첫 호출은 22초까지 기다리고,
 *       시간 초과면 다시 불러도 또 느릴 가능성이 높아 재시도 없이 규칙 기반 답을 준다. 스로틀링처럼 빨리 실패한 경우만
 *       남은 시간이 8초 이상이면 한 번 더 부른다.</li>
 *   <li>제한: 점포당 하루(KST) 50번, 파드당 동시 5개. 둘 다 파드 메모리라 파드 2개면 실제 한도는 대략 두 배다.</li>
 *   <li>캐시: 버튼 질문의 AI 답은 점포·질문 번호별 10분. 자유 질문과 규칙 기반 답은 캐시하지 않는다.</li>
 *   <li>로그: 질문 문장·번호·source·토큰 수·걸린 시간만. 요약 JSON과 답 전체는 남기지 않는다.</li>
 * </ul>
 */
@Service
public class AssistantService {

    static final Duration TOTAL_BUDGET = Duration.ofSeconds(25);
    static final Duration FIRST_CALL_TIMEOUT = Duration.ofSeconds(22);
    static final Duration MIN_RETRY_TIME = Duration.ofSeconds(8);
    static final int DAILY_LIMIT_PER_STORE = 50;
    static final int MAX_CONCURRENT = 5;
    static final Duration ANSWER_CACHE_TTL = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final AssistantProperties properties;
    private final AssistantModel model;
    private final StoreSummaryService summaryService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    private final Semaphore concurrent = new Semaphore(MAX_CONCURRENT);
    private final Map<String, AtomicInteger> dailyCounts = new ConcurrentHashMap<>();
    private final Map<String, CachedAnswer> answerCache = new ConcurrentHashMap<>();

    private record CachedAnswer(AssistantAnswer answer, LocalDateTime expiresAt) {
    }

    public AssistantService(
            AssistantProperties properties,
            AssistantModel model,
            StoreSummaryService summaryService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.properties = properties;
        this.model = model;
        this.summaryService = summaryService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public AssistantAnswer ask(Long storeId, AskRequest request) {
        AssistantPreset preset = request.presetId() == null ? null : AssistantPreset.of(request.presetId()).orElse(null);
        String questionTag = preset == null ? "FREE" : preset.name();
        long started = System.nanoTime();

        if (!properties.enabled()) {
            throw new BusinessException(InventoryErrorCode.ASSISTANT_DISABLED);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String cacheKey = preset == null ? null : storeId + ":" + preset.name();
        if (cacheKey != null) {
            CachedAnswer cached = answerCache.get(cacheKey);
            if (cached != null && now.isBefore(cached.expiresAt())) {
                Counter.builder("assistant.cache.hits").tag("kind", "answer").register(meterRegistry).increment();
                record(questionTag, "ai", started);
                return cached.answer();
            }
        }
        if (countToday(storeId, now.toLocalDate()) > DAILY_LIMIT_PER_STORE) {
            record(questionTag, "limited", started);
            throw new BusinessException(InventoryErrorCode.ASSISTANT_RATE_LIMITED);
        }
        if (!concurrent.tryAcquire()) {
            record(questionTag, "busy", started);
            throw new BusinessException(InventoryErrorCode.ASSISTANT_BUSY);
        }
        try {
            String question = preset == null ? request.question().strip() : preset.question();
            StoreSummary summary = summaryService.build(storeId);
            AssistantAnswer answer = answer(storeId, preset, question, summary, started);
            if (cacheKey != null && AssistantAnswer.AI.equals(answer.source())) {
                answerCache.put(cacheKey, new CachedAnswer(answer, now.plus(ANSWER_CACHE_TTL)));
            }
            record(questionTag, AssistantAnswer.AI.equals(answer.source()) ? "ai" : "rule", started);
            return answer;
        } finally {
            concurrent.release();
        }
    }

    private AssistantAnswer answer(Long storeId, AssistantPreset preset, String question, StoreSummary summary,
                                   long started) {
        if (!properties.aiConfigured()) {
            return rule(preset, summary);
        }
        String user = AssistantPrompt.userMessage(objectMapper.writeValueAsString(summary), question);
        ModelAnswer modelAnswer;
        try {
            modelAnswer = model.ask(AssistantPrompt.SYSTEM, user, FIRST_CALL_TIMEOUT);
        } catch (AssistantModelException first) {
            Duration remaining = TOTAL_BUDGET.minusNanos(System.nanoTime() - started);
            if (first.timedOut() || remaining.compareTo(MIN_RETRY_TIME) < 0) {
                log.warn("AI 비서 호출 실패, 규칙 답으로: storeId={}, question={}, timedOut={}, 이유={}",
                        storeId, question, first.timedOut(), first.getMessage());
                return rule(preset, summary);
            }
            try {
                modelAnswer = model.ask(AssistantPrompt.SYSTEM, user, remaining);
            } catch (AssistantModelException second) {
                log.warn("AI 비서 재시도 실패, 규칙 답으로: storeId={}, question={}, 이유={}",
                        storeId, question, second.getMessage());
                return rule(preset, summary);
            }
        }
        Counter.builder("assistant.tokens").tag("kind", "input").register(meterRegistry)
                .increment(modelAnswer.inputTokens());
        Counter.builder("assistant.tokens").tag("kind", "output").register(meterRegistry)
                .increment(modelAnswer.outputTokens());
        log.info("AI 비서 답: storeId={}, preset={}, question={}, inputTokens={}, outputTokens={}, elapsedMs={}",
                storeId, preset, question, modelAnswer.inputTokens(), modelAnswer.outputTokens(),
                Duration.ofNanos(System.nanoTime() - started).toMillis());
        return new AssistantAnswer(modelAnswer.text(), AssistantAnswer.AI, nowOffset());
    }

    private AssistantAnswer rule(AssistantPreset preset, StoreSummary summary) {
        return new AssistantAnswer(RuleAnswers.answer(preset, summary), AssistantAnswer.RULE, nowOffset());
    }

    /** 오늘(KST) 이 점포의 질문 수를 하나 올리고 돌려준다. 지난 날짜 칸은 그때 지운다 */
    private int countToday(Long storeId, LocalDate today) {
        String key = storeId + ":" + today;
        dailyCounts.keySet().removeIf(k -> !k.endsWith(":" + today));
        return dailyCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private void record(String question, String result, long started) {
        Timer.builder("assistant.ask")
                .tag("question", question)
                .tag("result", result)
                .register(meterRegistry)
                .record(Duration.ofNanos(System.nanoTime() - started));
    }

    private OffsetDateTime nowOffset() {
        return OffsetDateTime.now(clock).atZoneSameInstant(SEOUL).toOffsetDateTime();
    }
}
