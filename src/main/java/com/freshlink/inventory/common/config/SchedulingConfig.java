package com.freshlink.inventory.common.config;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 유통기한 스케줄러(E-09)용. 파드가 2개라 ShedLock으로 한 파드만 돌게 한다.
 * 잠금은 LockConfig의 LockProvider 빈 하나를 common Outbox 발행기와 같이 쓴다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulingConfig {
}
