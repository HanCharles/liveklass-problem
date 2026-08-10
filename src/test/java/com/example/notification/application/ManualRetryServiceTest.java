package com.example.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.notification.common.ApiException;
import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationStatus;
import com.example.notification.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

class ManualRetryServiceTest extends AbstractIntegrationTest {

    @Test
    void retry_movesFailedNotificationBackToReadyAndResetsAttemptCount() {
        Notification notification = register("user-1", "evt-manual-retry", NotificationChannel.EMAIL);
        mockEmailSender.alwaysFail(notification.getEventId());

        notificationProcessor.processOnce();
        forceNextAttemptAt(notification.getId(), clock.instant().minusSeconds(1));
        notificationProcessor.processOnce();
        forceNextAttemptAt(notification.getId(), clock.instant().minusSeconds(1));
        notificationProcessor.processOnce();

        Notification failed = notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);

        Notification retried = manualRetryService.retry(notification.getId());

        assertThat(retried.getStatus()).isEqualTo(NotificationStatus.READY);
        assertThat(retried.getAttemptCount()).isZero();
        assertThat(retried.getNextAttemptAt()).isNotNull();
    }

    @Test
    void retry_rejectsNotificationThatIsNotFailed() {
        Notification notification = register("user-1", "evt-not-failed", NotificationChannel.EMAIL);

        assertThatThrownBy(() -> manualRetryService.retry(notification.getId()))
                .isInstanceOf(ApiException.class);
    }
}
