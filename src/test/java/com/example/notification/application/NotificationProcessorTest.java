package com.example.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.notification.domain.AttemptStatus;
import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationAttempt;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationStatus;
import com.example.notification.domain.NotificationType;
import com.example.notification.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationProcessorTest extends AbstractIntegrationTest {

    @Test
    void processOnce_sendsReadyNotificationAndMarksSent() {
        Notification notification = register("user-1", "evt-sent", NotificationChannel.EMAIL);

        int processed = notificationProcessor.processOnce();

        assertThat(processed).isEqualTo(1);
        Notification reloaded =
                notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(reloaded.getSentAt()).isNotNull();

        List<NotificationAttempt> attempts =
                notificationAttemptRepository.findByNotificationIdOrderByAttemptNoAsc(notification.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo(AttemptStatus.SUCCESS);
        assertThat(attempts.get(0).getAttemptNo()).isEqualTo(1);
    }

    @Test
    void processOnce_onSenderFailure_movesToRetryWaitingAndIncrementsAttemptCount() {
        Notification notification = register("user-1", "evt-fail-once", NotificationChannel.EMAIL);
        mockEmailSender.failNext(notification.getEventId(), 1);

        notificationProcessor.processOnce();

        Notification reloaded =
                notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.RETRY_WAITING);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded.getLastFailureReason()).isNotBlank();
        assertThat(reloaded.getNextAttemptAt()).isAfter(reloaded.getUpdatedAt());

        List<NotificationAttempt> attempts =
                notificationAttemptRepository.findByNotificationIdOrderByAttemptNoAsc(notification.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo(AttemptStatus.FAILURE);
    }

    @Test
    void processOnce_exceedingMaxAttempts_movesToFailed() {
        Notification notification = register("user-1", "evt-always-fail", NotificationChannel.EMAIL);
        mockEmailSender.alwaysFail(notification.getEventId());

        // 테스트 프로파일 max-attempts=3. 매 시도마다 nextAttemptAt을 강제로 과거로 돌려 즉시 재처리되게 한다.
        notificationProcessor.processOnce();
        forceNextAttemptAt(notification.getId(), clock.instant().minusSeconds(1));
        notificationProcessor.processOnce();
        forceNextAttemptAt(notification.getId(), clock.instant().minusSeconds(1));
        notificationProcessor.processOnce();

        Notification reloaded =
                notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(reloaded.getAttemptCount()).isEqualTo(3);
        assertThat(reloaded.getNextAttemptAt()).isNull();

        List<NotificationAttempt> attempts =
                notificationAttemptRepository.findByNotificationIdOrderByAttemptNoAsc(notification.getId());
        assertThat(attempts).hasSize(3);
        assertThat(attempts).allMatch(a -> a.getStatus() == AttemptStatus.FAILURE);
    }

    @Test
    void processOnce_onlyClaimsDueRetryWaitingNotifications() {
        Notification dueNotification = register("user-1", "evt-due", NotificationChannel.EMAIL);
        mockEmailSender.failNext(dueNotification.getEventId(), 1);
        notificationProcessor.processOnce(); // READY -> RETRY_WAITING
        forceNextAttemptAt(dueNotification.getId(), clock.instant().minusSeconds(5)); // 이제 due 상태

        Notification futureNotification = register("user-1", "evt-future", NotificationChannel.EMAIL);
        mockEmailSender.failNext(futureNotification.getEventId(), 1);
        notificationProcessor.processOnce(); // READY -> RETRY_WAITING
        forceNextAttemptAt(futureNotification.getId(), clock.instant().plusSeconds(3600)); // 명확히 미래

        // 이 시점에 두 알림 모두 이미 실패 1회 소비했으므로 다음 send는 성공 처리됨(mock이 실패 횟수 소진)
        notificationProcessor.processOnce();

        Notification dueReloaded =
                notificationRepository.findById(dueNotification.getId()).orElseThrow();
        Notification futureReloaded =
                notificationRepository.findById(futureNotification.getId()).orElseThrow();

        assertThat(dueReloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(futureReloaded.getStatus()).isEqualTo(NotificationStatus.RETRY_WAITING);
    }

    @Test
    void processOnce_dispatchesToChannelSpecificSender() {
        Notification emailNotification = register("user-1", "evt-email-branch", NotificationChannel.EMAIL);
        mockEmailSender.alwaysFail(emailNotification.getEventId());
        Notification inAppNotification = register("user-1", "evt-inapp-branch", NotificationChannel.IN_APP);

        notificationProcessor.processOnce();

        Notification emailReloaded =
                notificationRepository.findById(emailNotification.getId()).orElseThrow();
        Notification inAppReloaded =
                notificationRepository.findById(inAppNotification.getId()).orElseThrow();

        assertThat(emailReloaded.getStatus()).isEqualTo(NotificationStatus.RETRY_WAITING);
        assertThat(inAppReloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void processOnce_dispatchesToKakaoAlimtalkSender() {
        Notification notification = register("user-1", "evt-kakao-branch", NotificationChannel.KAKAO_ALIMTALK);

        notificationProcessor.processOnce();

        Notification reloaded =
                notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void processOnce_circuitBreakerOpensAfterRepeatedFailures_andShortCircuitsFurtherSendCalls() {
        // 테스트 프로파일: notification.circuit-breaker.sliding-window-size=3, minimum-number-of-calls=3,
        // failure-rate-threshold=50. 앞 3건이 모두 실패하면 실패율 100%로 CircuitBreaker가 OPEN되어
        // 나머지 건은 sender.send() 자체를 호출하지 않고 즉시 실패 처리(short-circuit)된다.
        List<Notification> notifications = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Notification n = register("user-1", "evt-cb-" + i, NotificationChannel.EMAIL);
            mockEmailSender.alwaysFail(n.getEventId());
            notifications.add(n);
        }

        notificationProcessor.processOnce();

        assertThat(mockEmailSender.getSendAttemptCount()).isEqualTo(3);

        List<Notification> reloaded = notifications.stream()
                .map(n -> notificationRepository.findById(n.getId()).orElseThrow())
                .toList();

        // 실제로 sender.send()가 호출되어 실패한 3건: attemptCount가 증가하고 시도 이력이 남는다.
        List<Notification> realFailures =
                reloaded.stream().filter(n -> n.getAttemptCount() == 1).toList();
        assertThat(realFailures).hasSize(3);
        assertThat(realFailures).allMatch(n -> n.getStatus() == NotificationStatus.RETRY_WAITING);
        for (Notification n : realFailures) {
            List<NotificationAttempt> attempts =
                    notificationAttemptRepository.findByNotificationIdOrderByAttemptNoAsc(n.getId());
            assertThat(attempts).hasSize(1);
            assertThat(attempts.get(0).getStatus()).isEqualTo(AttemptStatus.FAILURE);
        }

        // CircuitBreaker OPEN으로 short-circuit된 나머지 2건: attemptCount는 그대로, 시도 이력도 안 남는다.
        List<Notification> circuitOpen =
                reloaded.stream().filter(n -> n.getAttemptCount() == 0).toList();
        assertThat(circuitOpen).hasSize(2);
        assertThat(circuitOpen).allMatch(n -> n.getStatus() == NotificationStatus.RETRY_WAITING);
        assertThat(circuitOpen)
                .allMatch(n -> n.getLastFailureReason() != null
                        && n.getLastFailureReason().toLowerCase().contains("circuit breaker"));
        for (Notification n : circuitOpen) {
            assertThat(notificationAttemptRepository.findByNotificationIdOrderByAttemptNoAsc(n.getId()))
                    .isEmpty();
        }
    }

    @Test
    void processOnce_circuitBreakerOpen_doesNotIncrementAttemptCountAndKeepsRetryWaiting() {
        // 먼저 EMAIL CircuitBreaker를 OPEN시킨다(테스트 프로파일 minimum-number-of-calls=3).
        for (int i = 0; i < 3; i++) {
            Notification warmup = register("user-1", "evt-cb-warmup-" + i, NotificationChannel.EMAIL);
            mockEmailSender.alwaysFail(warmup.getEventId());
        }
        notificationProcessor.processOnce();
        int sendAttemptsAfterWarmup = mockEmailSender.getSendAttemptCount();

        // circuit이 OPEN인 상태에서 새 알림을 등록해 claim되게 한다.
        Notification target = register("user-1", "evt-cb-target", NotificationChannel.EMAIL);

        notificationProcessor.processOnce();

        assertThat(mockEmailSender.getSendAttemptCount()).isEqualTo(sendAttemptsAfterWarmup);
        Notification reloaded = notificationRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getAttemptCount()).isZero();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.RETRY_WAITING);
        assertThat(reloaded.getLastFailureReason()).containsIgnoringCase("circuit breaker");
        assertThat(reloaded.getNextAttemptAt()).isAfter(clock.instant());
        assertThat(notificationAttemptRepository.findByNotificationIdOrderByAttemptNoAsc(target.getId()))
                .isEmpty();
    }

    @Test
    void processOnce_doesNotClaimNotificationScheduledForTheFuture() {
        Instant future = clock.instant().plusSeconds(3600);
        Notification scheduled = notificationCommandService.register(new NotificationCommandService.RegisterCommand(
                "user-1",
                NotificationType.ENROLLMENT_COMPLETED,
                "evt-scheduled",
                "course-1",
                NotificationChannel.EMAIL,
                Map.of("courseTitle", "Spring Boot 입문"),
                future));

        int processed = notificationProcessor.processOnce();

        assertThat(processed).isZero();
        Notification reloaded =
                notificationRepository.findById(scheduled.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.READY);
        assertThat(reloaded.getScheduledAt()).isEqualTo(future);
    }

    @Test
    void processOnce_claimsScheduledNotificationOnceItsTimeHasPassed() {
        Instant future = clock.instant().plusSeconds(3600);
        Notification scheduled = notificationCommandService.register(new NotificationCommandService.RegisterCommand(
                "user-1",
                NotificationType.ENROLLMENT_COMPLETED,
                "evt-scheduled-due",
                "course-1",
                NotificationChannel.EMAIL,
                Map.of("courseTitle", "Spring Boot 입문"),
                future));
        // 실제 예약 시각까지 기다리는 대신, 이미 그 시각이 지난 것처럼 강제로 되돌려 결정적으로 검증한다.
        forceNextAttemptAt(scheduled.getId(), clock.instant().minusSeconds(1));

        notificationProcessor.processOnce();

        Notification reloaded =
                notificationRepository.findById(scheduled.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(reloaded.getScheduledAt()).isEqualTo(future);
    }
}
