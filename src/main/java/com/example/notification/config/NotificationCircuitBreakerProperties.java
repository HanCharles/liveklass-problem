package com.example.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * notification.circuit-breaker.* 설정 바인딩.
 *
 * 채널별 {@code NotificationSender}를 감싸는 CircuitBreaker(resilience4j) 설정이다.
 * 외부 발송 API가 연속으로 실패할 때 계속 헛되이 호출하지 않고 빠르게 실패 처리(fail-fast)해
 * 외부 시스템과 이쪽 worker 스레드를 모두 보호하기 위한 목적이며, 재시도(attemptCount/backoff)
 * 상태 전이 로직 자체는 건드리지 않는다 — CircuitBreaker가 OPEN이면 그 시도 자체가 하나의
 * "실패"로 기록되어 기존 {@code RetryPolicy}에 따라 그대로 재시도 대상이 된다.
 */
@ConfigurationProperties(prefix = "notification.circuit-breaker")
public record NotificationCircuitBreakerProperties(
        int slidingWindowSize,
        int minimumNumberOfCalls,
        float failureRateThreshold,
        long waitDurationInOpenStateSeconds) {
}
