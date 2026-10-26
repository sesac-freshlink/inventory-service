package com.freshlink.inventory;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 지금 시각을 2026-10-25 12:00 KST로 고정한다.
 * 이 설정을 쓰는 테스트는 설정을 똑같이 맞춰 Spring 컨텍스트 하나를 같이 쓴다. 클래스마다 설정이 다르면 앱이 한 번 더 떠서
 * 테스트가 느려진다. MySQL 컨테이너는 컨텍스트와 상관없이 하나다(TestcontainersConfiguration).
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfiguration {

    public static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-25T12:00:00+09:00");

    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(NOW.toInstant(), ZoneId.of("Asia/Seoul"));
    }
}
