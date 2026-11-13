package com.freshlink.inventory.domain.assistant;

import java.time.Duration;
import java.util.Objects;
import java.util.stream.Collectors;

import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

/**
 * Bedrock Converse API로 Claude를 부른다. 모델이 바뀌어도 요청 모양이 같다.
 * 자격 증명·리전은 파드의 IRSA가 넣어 준다(코드에 키 없음). 도구 호출은 쓰지 않는다(답만 한다)
 */
public class BedrockAssistantModel implements AssistantModel {

    /** 답 길이 상한(문서 3-3 4번) */
    static final int MAX_TOKENS = 1000;

    private final BedrockRuntimeClient client;
    private final String modelId;

    public BedrockAssistantModel(BedrockRuntimeClient client, String modelId) {
        this.client = client;
        this.modelId = modelId;
    }

    @Override
    public ModelAnswer ask(String system, String user, Duration timeout) {
        ConverseRequest request = ConverseRequest.builder()
                .modelId(modelId)
                .system(SystemContentBlock.fromText(system))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(user))
                        .build())
                .inferenceConfig(InferenceConfiguration.builder().maxTokens(MAX_TOKENS).build())
                .overrideConfiguration(config -> config.apiCallTimeout(timeout))
                .build();
        ConverseResponse response;
        try {
            response = client.converse(request);
        } catch (ApiCallTimeoutException e) {
            throw new AssistantModelException("Bedrock 응답 시간 초과: " + timeout, true, e);
        } catch (SdkException e) {
            throw new AssistantModelException("Bedrock 호출 실패: " + e.getMessage(), false, e);
        }
        String text = response.output().message().content().stream()
                .map(ContentBlock::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
        TokenUsage usage = response.usage();
        return new ModelAnswer(
                text,
                usage == null || usage.inputTokens() == null ? 0 : usage.inputTokens(),
                usage == null || usage.outputTokens() == null ? 0 : usage.outputTokens());
    }
}
