package com.example.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.notification.domain.AttemptStatus;
import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationAttempt;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationStatus;
import com.example.notification.support.AbstractIntegrationTest;
import java.util.List;
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
}
