package com.example.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationTest {

    private final RetryPolicy retryPolicy = new RetryPolicy(3, List.of(1L, 2L, 3L));
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void create_startsInReadyStatusWithComputedIdempotencyKey() {
        Notification notification = sample();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.READY);
        assertThat(notification.getAttemptCount()).isZero();
        assertThat(notification.getNextAttemptAt()).isEqualTo(now);
        assertThat(notification.getIdempotencyKey()).isEqualTo("user-1|ENROLLMENT_COMPLETED|evt-1|EMAIL");
    }

    @Test
    void markRead_isIdempotentAndKeepsFirstReadAt() {
        Notification notification = sample();

        notification.markRead(now);
        Instant firstReadAt = notification.getReadAt();

        notification.markRead(now.plusSeconds(10));

        assertThat(notification.getReadAt()).isEqualTo(firstReadAt);
    }

    @Test
    void markSent_transitionsToSentAndClearsProcessingFields() {
        Notification notification = sample();

        notification.markSent(now);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSentAt()).isEqualTo(now);
        assertThat(notification.getWorkerId()).isNull();
        assertThat(notification.getLeaseUntil()).isNull();
    }

    @Test
    void recordFailure_movesToRetryWaitingWhileAttemptsRemain() {
        Notification notification = sample();

        notification.recordFailure("smtp timeout", retryPolicy, now);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.RETRY_WAITING);
        assertThat(notification.getAttemptCount()).isEqualTo(1);
        assertThat(notification.getLastFailureReason()).isEqualTo("smtp timeout");
        assertThat(notification.getNextAttemptAt()).isEqualTo(now.plusSeconds(1));
    }

    @Test
    void recordFailure_movesToFailedWhenMaxAttemptsExceeded() {
        Notification notification = sample();

        notification.recordFailure("e1", retryPolicy, now);
        notification.recordFailure("e2", retryPolicy, now);
        notification.recordFailure("e3", retryPolicy, now);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getAttemptCount()).isEqualTo(3);
        assertThat(notification.getNextAttemptAt()).isNull();
    }

    @Test
    void retryManually_resetsAttemptCountAndReturnsToReady() {
        Notification notification = sample();
        notification.recordFailure("e1", retryPolicy, now);
        notification.recordFailure("e2", retryPolicy, now);
        notification.recordFailure("e3", retryPolicy, now);

        notification.retryManually(now.plusSeconds(100));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.READY);
        assertThat(notification.getAttemptCount()).isZero();
        assertThat(notification.getNextAttemptAt()).isEqualTo(now.plusSeconds(100));
    }

    private Notification sample() {
        return Notification.create(
                "user-1",
                NotificationType.ENROLLMENT_COMPLETED,
                "evt-1",
                "course-1",
                NotificationChannel.EMAIL,
                "title",
                "message",
                Map.of(),
                3,
                now);
    }
}
