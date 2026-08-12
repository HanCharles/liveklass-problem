package com.example.notification.application;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationAttempt;
import com.example.notification.domain.RetryPolicy;
import com.example.notification.infrastructure.persistence.NotificationAttemptRepository;
import com.example.notification.infrastructure.persistence.NotificationRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * worker가 claim한 알림의 발송 시도 결과(성공/실패)를 반영하는 서비스.
 *
 * {@link com.example.notification.infrastructure.worker.NotificationWorker}가 sender를
 * 트랜잭션 밖에서 호출한 뒤, 그 결과만 여기서 별도의 짧은 트랜잭션으로 기록한다.
 * 실패 시 재시도 가능 여부와 다음 재시도 시각은 {@link RetryPolicy}가 결정한다.
 */
@Service
@RequiredArgsConstructor
public class NotificationRetryService {

    private final NotificationRepository notificationRepository;
    private final NotificationAttemptRepository attemptRepository;
    private final RetryPolicy retryPolicy;

    @Transactional
    public void recordSuccess(UUID notificationId, int attemptNo, Instant startedAt, Instant finishedAt) {
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null) {
            return;
        }
        notification.markSent(finishedAt);
        attemptRepository.save(NotificationAttempt.success(notificationId, attemptNo, startedAt, finishedAt));
    }

    @Transactional
    public void recordFailure(
            UUID notificationId, int attemptNo, String failureReason, Instant startedAt, Instant finishedAt) {
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null) {
            return;
        }
        notification.recordFailure(failureReason, retryPolicy, finishedAt);
        attemptRepository.save(
                NotificationAttempt.failure(notificationId, attemptNo, failureReason, startedAt, finishedAt));
    }

    /**
     * CircuitBreaker OPEN으로 인한 short-circuit을 기록한다. 실제 sender 호출이 없었으므로
     * {@code notification_attempt}에는 남기지 않는다(그 테이블은 실제 발송 시도만 남기는
     * 용도로 유지한다) — 대신 {@code notification.lastFailureReason}으로 사유를 남긴다.
     */
    @Transactional
    public void recordCircuitOpen(UUID notificationId, String reason, Instant nextAttemptAt, Instant now) {
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null) {
            return;
        }
        notification.recordCircuitOpen(reason, nextAttemptAt, now);
    }
}
