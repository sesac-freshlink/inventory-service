package com.freshlink.inventory.domain.assistant;

import java.util.Arrays;
import java.util.Optional;

/** 자주 묻는 질문 5개 (문서 1장). 버튼은 미리 써 둔 질문 문장일 뿐이고 자유 질문과 같은 길을 탄다 */
public enum AssistantPreset {

    Q1("오늘까지 유통기한이 끝나는 상품 알려줘"),
    Q2("이번 달 매출 기준으로 더 발주해야 할 상품 알려줘"),
    Q3("재고가 부족하거나 곧 떨어질 상품 알려줘"),
    Q4("시간대별로 잘 팔리는 상품 알려줘"),
    Q5("폐기가 많아서 덜 주문해야 할 상품 알려줘");

    private final String question;

    AssistantPreset(String question) {
        this.question = question;
    }

    public String question() {
        return question;
    }

    public static Optional<AssistantPreset> of(String id) {
        return Arrays.stream(values()).filter(p -> p.name().equals(id)).findFirst();
    }
}
