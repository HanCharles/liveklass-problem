package com.example.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy retryPolicy = new RetryPolicy(3, List.of(60L, 300L, 900L));

    @Test
    void canRetry_returnsTrueOnlyWhileAttemptsRemain() {
        assertThat(retryPolicy.canRetry(1)).isTrue();
        assertThat(retryPolicy.canRetry(2)).isTrue();
        assertThat(retryPolicy.canRetry(3)).isFalse();
        assertThat(retryPolicy.canRetry(4)).isFalse();
    }

    @Test
    void nextAttemptAt_followsBackoffScheduleByAttemptCount() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(retryPolicy.nextAttemptAt(1, now)).isEqualTo(now.plusSeconds(60));
        assertThat(retryPolicy.nextAttemptAt(2, now)).isEqualTo(now.plusSeconds(300));
        assertThat(retryPolicy.nextAttemptAt(3, now)).isEqualTo(now.plusSeconds(900));
    }

    @Test
    void nextAttemptAt_usesLastBackoffValueWhenAttemptCountExceedsSchedule() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(retryPolicy.nextAttemptAt(10, now)).isEqualTo(now.plusSeconds(900));
    }
}
