package com.freshlink.inventory.domain.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 점주 비서 설정 (.github docs/기능이해/AI-점주-비서.md 3-3).
 *
 * @param enabled 기능 켜기(ASSISTANT_ENABLED). 기본 false면 API가 503 ASSISTANT_DISABLED
 * @param modelId Bedrock 모델 ID(BEDROCK_MODEL_ID). 비어 있으면 AI를 부르지 않고 규칙 기반 답을 준다
 */
@ConfigurationProperties("app.assistant")
public record AssistantProperties(boolean enabled, String modelId) {

    /** 모델 ID가 있어 Bedrock을 부를 수 있는지 */
    public boolean aiConfigured() {
        return modelId != null && !modelId.isBlank();
    }
}
