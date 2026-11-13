package com.freshlink.inventory.domain.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * AI 비서 질문. presetId(Q1~Q5)와 question(직접 입력) 중 정확히 하나를 보낸다. 틀리면 400 VALIDATION_ERROR
 */
@Schema(description = "AI 비서 질문. presetId(Q1~Q5 버튼)와 question(직접 입력, 공백 빼고 1~200자) 중 정확히 하나")
public record AskRequest(
        @Pattern(regexp = "Q[1-5]", message = "presetId는 Q1~Q5입니다")
        String presetId,
        @Size(max = MAX_QUESTION_LENGTH, message = "질문은 200자까지입니다")
        String question
) {

    public static final int MAX_QUESTION_LENGTH = 200;

    /** 버튼과 직접 입력 중 정확히 하나. 공백만 있는 질문은 없는 것으로 본다 */
    @AssertTrue(message = "presetId와 question 중 하나만 보내 주세요")
    public boolean isOneQuestion() {
        boolean hasQuestion = question != null && !question.isBlank();
        return (presetId != null) != hasQuestion;
    }
}
