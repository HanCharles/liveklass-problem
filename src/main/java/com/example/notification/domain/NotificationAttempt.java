package com.example.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 알림 발송 시도별 이력. notification.last_failure_reason은 최신값만, 이 테이블은 시도별 전체 이력을 남긴다. */
@Entity
@Table(name = "notification_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AttemptStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static NotificationAttempt success(
            UUID notificationId, int attemptNo, Instant startedAt, Instant finishedAt) {
        NotificationAttempt attempt = new NotificationAttempt();
        attempt.notificationId = notificationId;
        attempt.attemptNo = attemptNo;
        attempt.status = AttemptStatus.SUCCESS;
        attempt.startedAt = startedAt;
        attempt.finishedAt = finishedAt;
        attempt.createdAt = finishedAt;
        return attempt;
    }

    public static NotificationAttempt failure(
            UUID notificationId, int attemptNo, String failureReason, Instant startedAt, Instant finishedAt) {
        NotificationAttempt attempt = new NotificationAttempt();
        attempt.notificationId = notificationId;
        attempt.attemptNo = attemptNo;
        attempt.status = AttemptStatus.FAILURE;
        attempt.failureReason = failureReason;
        attempt.startedAt = startedAt;
        attempt.finishedAt = finishedAt;
        attempt.createdAt = finishedAt;
        return attempt;
    }
}
