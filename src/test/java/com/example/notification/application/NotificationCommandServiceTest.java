package com.example.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationStatus;
import com.example.notification.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

class NotificationCommandServiceTest extends AbstractIntegrationTest {

    @Test
    void register_createsNewNotificationInReadyStatus() {
        Notification notification = register("user-1", "evt-1", NotificationChannel.EMAIL);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.READY);
        assertThat(notificationRepository.findById(notification.getId())).isPresent();
    }

    @Test
    void register_withSameIdempotencyKey_returnsExistingNotification() {
        Notification first = register("user-1", "evt-1", NotificationChannel.EMAIL);
        Notification second = register("user-1", "evt-1", NotificationChannel.EMAIL);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    void register_withDifferentChannel_createsSeparateNotification() {
        Notification email = register("user-1", "evt-1", NotificationChannel.EMAIL);
        Notification inApp = register("user-1", "evt-1", NotificationChannel.IN_APP);

        assertThat(email.getId()).isNotEqualTo(inApp.getId());
        assertThat(notificationRepository.count()).isEqualTo(2);
    }

    @Test
    void register_withDifferentRecipient_createsSeparateNotification() {
        Notification user1 = register("user-1", "evt-1", NotificationChannel.EMAIL);
        Notification user2 = register("user-2", "evt-1", NotificationChannel.EMAIL);

        assertThat(user1.getId()).isNotEqualTo(user2.getId());
        assertThat(notificationRepository.count()).isEqualTo(2);
    }
}
