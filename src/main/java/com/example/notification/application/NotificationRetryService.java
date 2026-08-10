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
}
