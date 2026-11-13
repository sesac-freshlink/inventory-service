package com.freshlink.inventory.domain.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

class BedrockAssistantModelTest {

    private final BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
    private final BedrockAssistantModel model = new BedrockAssistantModel(client, "global.anthropic.claude-sonnet-5-5");

    @Test
    void 지시문과_질문을_보내고_답_글자와_토큰_수를_돌려준다() {
        when(client.converse(any(ConverseRequest.class))).thenReturn(ConverseResponse.builder()
                .output(ConverseOutput.fromMessage(Message.builder()
                        .role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText("오늘 끝나는 상품은 "), ContentBlock.fromText("샐러드 4개예요."))
                        .build()))
                .usage(TokenUsage.builder().inputTokens(10).outputTokens(2).totalTokens(12).build())
                .build());

        ModelAnswer answer = model.ask("지시문", "{요약} 질문", Duration.ofSeconds(22));

        assertThat(answer).isEqualTo(new ModelAnswer("오늘 끝나는 상품은 샐러드 4개예요.", 10, 2));
        ArgumentCaptor<ConverseRequest> sent = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(client).converse(sent.capture());
        assertThat(sent.getValue().modelId()).isEqualTo("global.anthropic.claude-sonnet-5-5");
        assertThat(sent.getValue().system()).singleElement().extracting(b -> b.text()).isEqualTo("지시문");
        assertThat(sent.getValue().messages()).singleElement()
                .satisfies(m -> assertThat(m.content()).singleElement().extracting(ContentBlock::text).isEqualTo("{요약} 질문"));
        assertThat(sent.getValue().inferenceConfig().maxTokens()).isEqualTo(BedrockAssistantModel.MAX_TOKENS);
        assertThat(sent.getValue().additionalModelRequestFields().asMap().get("thinking").asMap().get("type").asString())
                .isEqualTo("between_tools");
        assertThat(sent.getValue().overrideConfiguration()).get()
                .satisfies(o -> assertThat(o.apiCallTimeout()).contains(Duration.ofSeconds(22)));
    }

    @Test
    void 답이_비어_있으면_실패로_본다() {
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(StopReason.END_TURN, "  "));

        assertThatThrownBy(() -> model.ask("s", "u", Duration.ofSeconds(22)))
                .isInstanceOfSatisfying(AssistantModelException.class, e -> assertThat(e.timedOut()).isFalse());
    }

    @Test
    void 답이_토큰_상한에서_잘렸으면_실패로_본다() {
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(StopReason.MAX_TOKENS, "이미 유통기"));

        assertThatThrownBy(() -> model.ask("s", "u", Duration.ofSeconds(22)))
                .isInstanceOfSatisfying(AssistantModelException.class, e -> assertThat(e.timedOut()).isFalse());
    }

    private static ConverseResponse response(StopReason stopReason, String text) {
        return ConverseResponse.builder()
                .stopReason(stopReason)
                .output(ConverseOutput.fromMessage(Message.builder()
                        .role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText(text))
                        .build()))
                .usage(TokenUsage.builder().inputTokens(10).outputTokens(1000).totalTokens(1010).build())
                .build();
    }

    @Test
    void 시간_초과는_timedOut_예외로_바꾼다() {
        when(client.converse(any(ConverseRequest.class)))
                .thenThrow(ApiCallTimeoutException.builder().message("timeout").build());

        assertThatThrownBy(() -> model.ask("s", "u", Duration.ofSeconds(22)))
                .isInstanceOfSatisfying(AssistantModelException.class, e -> assertThat(e.timedOut()).isTrue());
    }

    @Test
    void 스로틀링_같은_그밖의_실패는_timedOut이_아닌_예외로_바꾼다() {
        when(client.converse(any(ConverseRequest.class)))
                .thenThrow(ThrottlingException.builder().message("slow down").build());

        assertThatThrownBy(() -> model.ask("s", "u", Duration.ofSeconds(22)))
                .isInstanceOfSatisfying(AssistantModelException.class, e -> assertThat(e.timedOut()).isFalse());
    }
}
