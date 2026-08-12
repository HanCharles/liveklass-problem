package com.example.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 알림 발송 요청 및 처리 상태를 나타내는 애그리게이트.
 *
 * 상태 전이:
 * READY -> PROCESSING -> SENT
 * PROCESSING -> RETRY_WAITING -> PROCESSING
 * PROCESSING -> FAILED
 * PROCESSING -> READY (lease timeout 복구, 별도 bulk update로 처리)
 * FAILED -> READY (수동 재시도)
 */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    private UUID id;

    @Column(name = "recipient_id", nullable = false)
    private String recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false)
    private NotificationType notificationType;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "reference_id")
    private String referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private NotificationStatus status;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "message", nullable = false)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    /**
     * 등록 시 요청한 "발송 예약 시각"(선택 구현). {@code nextAttemptAt}은 재시도 때마다
     * 계속 갱신되는 운영용 필드라 원래 요청한 예약 시각을 잃어버리므로, 감사/조회 목적으로
     * 원본 요청값을 별도로 보존한다. 즉시 발송 요청이면 null이다.
     */
    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_failure_reason")
    private String lastFailureReason;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * @param scheduledAt 특정 시각 예약 발송을 원하면 그 시각, 즉시 처리 대상이면 null.
     *                    null이 아니면 status는 READY로 저장하되 {@code nextAttemptAt}을
     *                    이 시각으로 설정해 그 전까지는 worker가 claim하지 않게 한다
     *                    (claim 쿼리가 READY도 {@code next_attempt_at <= now} 조건을 검사함).
     */
    public static Notification create(
            String recipientId,
            NotificationType notificationType,
            String eventId,
            String referenceId,
            NotificationChannel channel,
            String title,
            String message,
            Map<String, Object> payload,
            Instant scheduledAt,
            int maxAttempts,
            Instant now) {
        Notification notification = new Notification();
        notification.id = UUID.randomUUID();
        notification.recipientId = recipientId;
        notification.notificationType = notificationType;
        notification.eventId = eventId;
        notification.referenceId = referenceId;
        notification.channel = channel;
        notification.status = NotificationStatus.READY;
        notification.title = title;
        notification.message = message;
        notification.payload = payload;
        notification.idempotencyKey = buildIdempotencyKey(recipientId, notificationType, eventId, channel);
        notification.attemptCount = 0;
        notification.maxAttempts = maxAttempts;
        notification.scheduledAt = scheduledAt;
        notification.nextAttemptAt = scheduledAt != null ? scheduledAt : now;
        notification.createdAt = now;
        notification.updatedAt = now;
        return notification;
    }

    public static String buildIdempotencyKey(
            String recipientId, NotificationType type, String eventId, NotificationChannel channel) {
        return recipientId + "|" + type + "|" + eventId + "|" + channel;
    }

    public void markSent(Instant now) {
        this.status = NotificationStatus.SENT;
        this.sentAt = now;
        this.workerId = null;
        this.processingStartedAt = null;
        this.leaseUntil = null;
        this.updatedAt = now;
    }

    /** 발송 실패를 기록하고 RetryPolicy에 따라 RETRY_WAITING 또는 FAILED로 전이한다. */
    public void recordFailure(String failureReason, RetryPolicy retryPolicy, Instant now) {
        this.attemptCount += 1;
        this.lastFailureReason = failureReason;
        this.workerId = null;
        this.processingStartedAt = null;
        this.leaseUntil = null;
        this.updatedAt = now;

        if (retryPolicy.canRetry(this.attemptCount)) {
            this.status = NotificationStatus.RETRY_WAITING;
            this.nextAttemptAt = retryPolicy.nextAttemptAt(this.attemptCount, now);
        } else {
            this.status = NotificationStatus.FAILED;
            this.nextAttemptAt = null;
        }
    }

    /**
     * CircuitBreaker가 OPEN이어서 실제 발송을 시도조차 하지 못하고 short-circuit된 경우 호출한다.
     * 외부 sender를 실제로 호출한 게 아니므로 발송 시도 실패로 보지 않는다 — attemptCount를
     * 증가시키지 않고, retryPolicy의 최대 횟수 판단과도 무관하게 항상 RETRY_WAITING으로
     * 되돌린다(circuit이 아직 열려있는 한 재시도 예산을 소모할 이유가 없다).
     */
    public void recordCircuitOpen(String reason, Instant nextAttemptAt, Instant now) {
        this.status = NotificationStatus.RETRY_WAITING;
        this.lastFailureReason = reason;
        this.nextAttemptAt = nextAttemptAt;
        this.workerId = null;
        this.processingStartedAt = null;
        this.leaseUntil = null;
        this.updatedAt = now;
    }

    /** 운영자 수동 재시도: FAILED -> READY, attemptCount 초기화. */
    public void retryManually(Instant now) {
        this.status = NotificationStatus.READY;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
        this.lastFailureReason = "manual retry requested";
        this.updatedAt = now;
    }
}
