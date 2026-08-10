package com.example.notification.infrastructure.worker;

import com.example.notification.infrastructure.persistence.NotificationClaimRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * worker가 PROCESSING으로 바꾼 뒤 서버가 죽거나 응답이 없어 lease_until이 지난(stuck) 알림을
 * 주기적으로 READY로 복구한다. 단순 lease 만료는 발송 실패로 보지 않으므로 attemptCount는
 * 증가시키지 않는다.
 *
 * 빈 자체는 항상 생성되어 테스트에서 {@link #recoverOnce()}를 직접 호출해 결정적으로 검증할 수
 * 있도록 하고, {@code notification.recovery.enabled=false}일 때는 스케줄 트리거만 아무 동작도
 * 하지 않도록 한다.
 */
@Component
public class StuckNotificationRecoveryWorker {

    private static final Logger log = LoggerFactory.getLogger(StuckNotificationRecoveryWorker.class);

    private final NotificationClaimRepository claimRepository;
    private final Clock clock;
    private final boolean enabled;

    public StuckNotificationRecoveryWorker(
            NotificationClaimRepository claimRepository,
            Clock clock,
            @Value("${notification.recovery.enabled}") boolean enabled) {
        this.claimRepository = claimRepository;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${notification.recovery.interval-ms}")
    public void scheduledRecover() {
        if (!enabled) {
            return;
        }
        recoverOnce();
    }

    public int recoverOnce() {
        int recovered = claimRepository.recoverStuck(clock.instant());
        if (recovered > 0) {
            log.info("recovered {} stuck notification(s) back to READY", recovered);
        }
        return recovered;
    }
}
