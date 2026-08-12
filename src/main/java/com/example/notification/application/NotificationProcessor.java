package com.example.notification.application;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.infrastructure.persistence.NotificationClaimRepository;
import com.example.notification.infrastructure.persistence.NotificationRepository;
import com.example.notification.infrastructure.sender.NotificationSender;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
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
 * 채널별로 CircuitBreaker(resilience4j)를 하나씩 두어, 특정 채널의 외부 발송 API가 연속으로
 * 실패하는 동안에는 그 채널의 후속 호출을 즉시 실패 처리(fail-fast)하고 헛되이 외부 API를
 * 호출하지 않는다.
 *
 * CircuitBreaker가 OPEN이어서 호출 자체가 막힌 경우({@link CallNotPermittedException})는
 * 일반 발송 실패와 구분한다 — 실제로 외부 API를 호출한 게 아니므로 발송 시도 실패로 보지
 * 않고, {@link NotificationRetryService#recordCircuitOpen}으로 넘겨 attemptCount를
 * 소모하지 않은 채 RETRY_WAITING으로 되돌린다. 실제 {@code sender.send()} 호출이 예외를
 * 던진 경우만 {@link NotificationRetryService#recordFailure}로 넘어가 attemptCount를
 * 증가시키고 기존 재시도 정책을 그대로 따른다.
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
    private final Map<NotificationChannel, CircuitBreaker> circuitBreakersByChannel;
    private final String workerId = "worker-" + UUID.randomUUID();

    public NotificationProcessor(
            NotificationClaimRepository claimRepository,
            NotificationRepository notificationRepository,
            NotificationRetryService retryService,
            List<NotificationSender> senders,
            Clock clock,
            CircuitBreakerConfig circuitBreakerConfig,
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
        this.circuitBreakersByChannel = senders.stream()
                .collect(Collectors.toMap(
                        NotificationSender::channel,
                        sender -> CircuitBreaker.of(sender.channel().name(), circuitBreakerConfig)));
    }

    public String workerId() {
        return workerId;
    }

    /**
     * 채널별 CircuitBreaker를 CLOSED 상태로 되돌리고 슬라이딩 윈도우를 비운다.
     * CircuitBreaker는 이 빈이 살아있는 동안(스프링 싱글턴) 채널별로 상태를 계속 누적하므로,
     * 테스트 간에 한 테스트에서 연 CircuitBreaker가 다음 테스트로 새어나가지 않도록
     * 테스트 {@code @BeforeEach}에서 호출한다.
     */
    public void resetCircuitBreakers() {
        circuitBreakersByChannel.values().forEach(CircuitBreaker::reset);
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
        CircuitBreaker circuitBreaker = circuitBreakersByChannel.get(notification.getChannel());
        Instant startedAt = clock.instant();

        try {
            if (sender == null) {
                throw new IllegalStateException("no sender registered for channel " + notification.getChannel());
            }
            circuitBreaker.executeRunnable(() -> sender.send(notification));
            retryService.recordSuccess(id, attemptNo, startedAt, clock.instant());
        } catch (CallNotPermittedException e) {
            log.warn(
                    "notification send short-circuited by open circuit breaker. id={}, channel={}",
                    id,
                    notification.getChannel());
            long waitMillis = circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1);
            Instant nextAttemptAt = clock.instant().plusMillis(waitMillis);
            retryService.recordCircuitOpen(
                    id,
                    "circuit breaker open for channel " + notification.getChannel(),
                    nextAttemptAt,
                    clock.instant());
        } catch (Exception e) {
            log.warn("notification send failed. id={}, reason={}", id, e.getMessage());
            retryService.recordFailure(id, attemptNo, e.getMessage(), startedAt, clock.instant());
        }
    }
}
