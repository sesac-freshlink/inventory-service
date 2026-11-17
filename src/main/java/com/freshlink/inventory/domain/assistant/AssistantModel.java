package com.freshlink.inventory.domain.assistant;

import java.time.Duration;

/** AI에 묻는 한 번의 호출. 테스트는 가짜 구현을 쓴다 */
public interface AssistantModel {

    /**
     * @param system  시스템 지시문
     * @param user    점포 요약 JSON과 질문
     * @param timeout 이 호출 하나의 최대 시간(SDK 재시도 포함)
     * @throws AssistantModelException 실패·시간 초과
     */
    ModelAnswer ask(String system, String user, Duration timeout);
}
