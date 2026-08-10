package com.example.notification.domain;

import java.time.Instant;
import java.util.List;

/**
 * attemptCount 기반 exponential backoff 재시도 정책.
 * 운영 기본값: 1분/5분/15분, 최대 3회 (application.yml notification.retry.*, config.AppConfig에서 빈으로 등록).
 * 테스트 프로파일에서는 1s/2s/3s로 오버라이드하여 빠르게 검증한다.
 */
public class RetryPolicy {

    private final int maxAttempts;
    private final List<Long> backoffSeconds;

    public RetryPolicy(int maxAttempts, List<Long> backoffSeconds) {
        this.maxAttempts = maxAttempts;
        this.backoffSeconds = backoffSeconds;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * 실패 후 attemptCount(이번 실패 포함 누적 횟수)를 기준으로 재시도 가능 여부를 판단한다.
     */
    public boolean canRetry(int attemptCountAfterFailure) {
        return attemptCountAfterFailure < maxAttempts;
    }

    /**
     * 실패 후 다음 재시도 시각을 계산한다. attemptCount가 backoff 목록 범위를 넘으면 마지막 값을 사용한다.
     */
    public Instant nextAttemptAt(int attemptCountAfterFailure, Instant now) {
        int index = Math.min(Math.max(attemptCountAfterFailure - 1, 0), backoffSeconds.size() - 1);
        long delaySeconds = backoffSeconds.get(index);
        return now.plusSeconds(delaySeconds);
    }
}
