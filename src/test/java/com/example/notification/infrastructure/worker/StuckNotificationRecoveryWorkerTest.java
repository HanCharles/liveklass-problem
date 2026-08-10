package com.example.notification.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationStatus;
import com.example.notification.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

class StuckNotificationRecoveryWorkerTest extends AbstractIntegrationTest {

    @Test
    void recoverOnce_movesExpiredProcessingNotificationsBackToReadyWithoutIncrementingAttemptCount() {
        Notification notification = register("user-1", "evt-stuck", NotificationChannel.EMAIL);
        forceProcessing(
                notification.getId(),
                "dead-worker",
                clock.instant().minusSeconds(120),
                clock.instant().minusSeconds(60));

        stuckNotificationRecoveryWorker.recoverOnce();

        Notification reloaded =
                notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.READY);
        assertThat(reloaded.getWorkerId()).isNull();
        assertThat(reloaded.getProcessingStartedAt()).isNull();
        assertThat(reloaded.getLeaseUntil()).isNull();
        assertThat(reloaded.getAttemptCount()).isZero();
        assertThat(reloaded.getLastFailureReason()).isEqualTo("processing lease expired");
    }

    @Test
    void recoverOnce_doesNotTouchProcessingNotificationsWithinLease() {
        Notification notification = register("user-1", "evt-not-stuck", NotificationChannel.EMAIL);
        forceProcessing(
                notification.getId(), "active-worker", clock.instant(), clock.instant().plusSeconds(60));

        stuckNotificationRecoveryWorker.recoverOnce();

        Notification reloaded =
                notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(reloaded.getWorkerId()).isEqualTo("active-worker");
    }
}
