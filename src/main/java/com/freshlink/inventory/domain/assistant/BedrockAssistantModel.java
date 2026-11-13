package com.freshlink.inventory.domain.assistant;

import java.time.Duration;
import java.util.Objects;
import java.util.stream.Collectors;

import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

/**
 * Bedrock Converse API로 Claude를 부른다. 모델이 바뀌어도 요청 모양이 같다.
 * 자격 증명·리전은 파드의 IRSA가 넣어 준다(코드에 키 없음). 도구 호출은 쓰지 않는다(답만 한다)
 */
public class BedrockAssistantModel implements AssistantModel {

    /** 답 길이 상한(문서 3-3 4번) */
    static final int MAX_TOKENS = 1000;

    /**
     * 생각(thinking) 끄기. Sonnet 5.5는 기본으로 생각을 하고 그 토큰도 maxTokens에 들어가서,
     * 요약이 크면 답 글자를 쓰기 전에 상한에 닿아 빈 답이 왔다(11/13 dev). 숫자는 요약에 이미 계산돼 있다
     */
    static final Document NO_THINKING = Document.mapBuilder()
            .putDocument("thinking", Document.mapBuilder().putString("type", "between_tools").build())
            .build();

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
                .additionalModelRequestFields(NO_THINKING)
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
        if (text.isBlank() || response.stopReason() == StopReason.MAX_TOKENS) {
            // 빈 답이나 중간에 잘린 답은 보여 주지 않고 규칙 답으로 넘긴다
            throw new AssistantModelException("Bedrock 답이 비었거나 잘림: stopReason=" + response.stopReasonAsString(), false, null);
        }
        TokenUsage usage = response.usage();
        return new ModelAnswer(
                text,
                usage == null || usage.inputTokens() == null ? 0 : usage.inputTokens(),
                usage == null || usage.outputTokens() == null ? 0 : usage.outputTokens());
    }
}
