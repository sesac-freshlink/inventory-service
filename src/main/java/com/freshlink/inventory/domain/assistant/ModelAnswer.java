package com.freshlink.inventory.domain.assistant;

/** AI 답 한 번. 토큰 수는 지표(assistant_tokens_total)와 비용 계산에 쓴다 */
public record ModelAnswer(String text, int inputTokens, int outputTokens) {
}
