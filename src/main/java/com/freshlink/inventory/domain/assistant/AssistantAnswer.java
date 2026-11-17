package com.freshlink.inventory.domain.assistant;

import java.time.OffsetDateTime;

/**
 * AI 비서 답 (문서 4-3).
 *
 * @param source AI(Claude 답) 또는 RULE(AI를 부를 수 없어 코드가 만든 답)
 */
public record AssistantAnswer(String answer, String source, OffsetDateTime generatedAt) {

    public static final String AI = "AI";
    public static final String RULE = "RULE";
}
