package com.example.notification.application;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.infrastructure.persistence.NotificationClaimRepository;
import com.example.notification.infrastructure.persistence.NotificationRepository;
import com.example.notification.infrastructure.sender.NotificationSender;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * READY / RETRY_WAITING 상태의 알림을 claim하여 실제 발송을 처리하는 핵심 orchestration 로직.
 *
 * 1. {@link NotificationClaimRepository}로 claim(짧은 트랜잭션, 커밋되어 다른 인스턴스에 즉시 반영됨)
 * 2. claim된 각 알림에 대해 sender를 트랜잭션 밖에서 호출(외부 호출이 DB 트랜잭션/커넥션을 붙잡지 않도록)
 * 3. 성공/실패 결과는 {@link NotificationRetryService}가 알림 건별 별도 트랜잭션으로 반영
 *
 * {@code @Scheduled}는 이 클래스를 직접 호출하지 않고
 * {@link com.example.notification.infrastructure.worker.NotificationWorker}를 통해 호출한다.
 * 테스트는 processOnce()를 직접 호출해 스케줄러 타이밍에 의존하지 않고 결정적으로 검증한다.
 */
@Component
public class NotificationProcessor {

    private static final Logger log = LoggerFactory.getLogger(NotificationProcessor.class);

    private final NotificationClaimRepository claimRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationRetryService retryService;
    private final Clock clock;
    private final int batchSize;
    private final int leaseSeconds;
    private final Map<NotificationChannel, NotificationSender> sendersByChannel;
    private final String workerId = "worker-" + UUID.randomUUID();

    public NotificationProcessor(
            NotificationClaimRepository claimRepository,
            NotificationRepository notificationRepository,
            NotificationRetryService retryService,
            List<NotificationSender> senders,
            Clock clock,
            @Value("${notification.worker.batch-size}") int batchSize,
            @Value("${notification.worker.lease-seconds}") int leaseSeconds) {
        this.claimRepository = claimRepository;
        this.notificationRepository = notificationRepository;
        this.retryService = retryService;
        this.clock = clock;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.sendersByChannel =
                senders.stream().collect(Collectors.toMap(NotificationSender::channel, Function.identity()));
    }

    public String workerId() {
        return workerId;
    }

    /** 발송 가능한 알림을 한 번 claim해서 처리한다. 처리한 건수를 반환한다. */
    public int processOnce() {
        Instant now = clock.instant();
        Instant leaseUntil = now.plusSeconds(leaseSeconds);
        List<UUID> claimedIds = claimRepository.claim(workerId, now, leaseUntil, batchSize);

        for (UUID id : claimedIds) {
            processSingle(id);
        }
        return claimedIds.size();
    }

    private void processSingle(UUID id) {
        Notification notification = notificationRepository.findById(id).orElse(null);
        if (notification == null) {
            return;
        }

        int attemptNo = notification.getAttemptCount() + 1;
        NotificationSender sender = sendersByChannel.get(notification.getChannel());
        Instant startedAt = clock.instant();

        try {
            if (sender == null) {
                throw new IllegalStateException("no sender registered for channel " + notification.getChannel());
            }
            sender.send(notification);
            retryService.recordSuccess(id, attemptNo, startedAt, clock.instant());
        } catch (Exception e) {
            log.warn("notification send failed. id={}, reason={}", id, e.getMessage());
            retryService.recordFailure(id, attemptNo, e.getMessage(), startedAt, clock.instant());
        }
    }
}
