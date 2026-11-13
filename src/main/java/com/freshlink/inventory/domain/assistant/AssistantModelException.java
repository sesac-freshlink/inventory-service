package com.freshlink.inventory.domain.assistant;

/**
 * AI 호출 실패. timedOut이면 시간 초과라 다시 불러도 또 느릴 가능성이 높아 재시도하지 않는다.
 * 그 밖의 실패(스로틀링, 일시 오류, 권한 오류)는 남은 시간이 있을 때 한 번 다시 부를 수 있다
 */
public class AssistantModelException extends RuntimeException {

    private final boolean timedOut;

    public AssistantModelException(String message, boolean timedOut, Throwable cause) {
        super(message, cause);
        this.timedOut = timedOut;
    }

    public boolean timedOut() {
        return timedOut;
    }
}
