package com.example.notification.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * notification.retry.* 설정 바인딩.
 *
 * YAML 시퀀스(리스트)는 {@code @Value("${...}")} placeholder로는 바로 바인딩되지 않고
 * (Spring이 인덱스 프로퍼티로 쪼개서 저장하기 때문) {@code @ConfigurationProperties}의
 * relaxed binding을 통해서만 List로 바인딩된다.
 */
@ConfigurationProperties(prefix = "notification.retry")
public record NotificationRetryProperties(int maxAttempts, List<Long> backoffSeconds) {
}
