package com.freshlink.inventory.domain.assistant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Bedrock 클라이언트는 모델 ID가 있을 때만 만든다. 로컬·테스트처럼 값이 없으면 AI를 부르지 않는 모델을 쓴다
 */
@Configuration
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${app.assistant.model-id:}' != ''")
    BedrockRuntimeClient bedrockRuntimeClient() {
        // 리전(AWS_REGION)과 자격 증명(IRSA)은 기본 공급자 체인이 환경에서 읽는다
        return BedrockRuntimeClient.create();
    }

    @Bean
    AssistantModel assistantModel(AssistantProperties properties, ObjectProvider<BedrockRuntimeClient> client) {
        BedrockRuntimeClient bedrock = client.getIfAvailable();
        if (bedrock == null || !properties.aiConfigured()) {
            return (system, user, timeout) -> {
                throw new AssistantModelException("BEDROCK_MODEL_ID가 없어 AI를 부르지 않는다", false, null);
            };
        }
        return new BedrockAssistantModel(bedrock, properties.modelId());
    }
}
