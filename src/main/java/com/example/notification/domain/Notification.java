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

    public static Notification create(
            String recipientId,
            NotificationType notificationType,
            String eventId,
            String referenceId,
            NotificationChannel channel,
            String title,
            String message,
            Map<String, Object> payload,
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
        notification.nextAttemptAt = now;
        notification.createdAt = now;
        notification.updatedAt = now;
        return notification;
    }

    public static String buildIdempotencyKey(
            String recipientId, NotificationType type, String eventId, NotificationChannel channel) {
        return recipientId + "|" + type + "|" + eventId + "|" + channel;
    }

    /** 이미 읽은 알림을 다시 읽음 처리해도 안전하도록 readAt이 있으면 갱신하지 않는다(멱등). */
    public void markRead(Instant now) {
        if (this.readAt == null) {
            this.readAt = now;
            this.updatedAt = now;
        }
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

    /** 운영자 수동 재시도: FAILED -> READY, attemptCount 초기화. */
    public void retryManually(Instant now) {
        this.status = NotificationStatus.READY;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
        this.lastFailureReason = "manual retry requested";
        this.updatedAt = now;
    }
}
