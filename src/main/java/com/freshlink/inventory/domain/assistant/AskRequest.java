package com.freshlink.inventory.domain.assistant;

/**
 * AI 비서 질문. presetId(Q1~Q5)와 question(직접 입력, 200자까지) 중 정확히 하나를 보낸다.
 * 검증은 컨트롤러에서 한다(Task 4)
 */
public record AskRequest(String presetId, String question) {
}
