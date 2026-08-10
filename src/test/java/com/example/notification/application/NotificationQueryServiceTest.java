package com.example.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.support.AbstractIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

class NotificationQueryServiceTest extends AbstractIntegrationTest {

    @Test
    void list_filtersByReadStatus() {
        Notification unread = register("user-list", "evt-unread", NotificationChannel.EMAIL);
        Notification readOne = register("user-list", "evt-read", NotificationChannel.IN_APP);
        notificationQueryService.markRead(readOne.getId());

        Page<Notification> unreadResults =
                notificationQueryService.list("user-list", false, null, null, PageRequest.of(0, 10));
        Page<Notification> readResults =
                notificationQueryService.list("user-list", true, null, null, PageRequest.of(0, 10));

        assertThat(unreadResults.getContent()).extracting(Notification::getId).containsExactly(unread.getId());
        assertThat(readResults.getContent()).extracting(Notification::getId).containsExactly(readOne.getId());
    }

    @Test
    void list_filtersByChannel() {
        Notification email = register("user-list-2", "evt-a", NotificationChannel.EMAIL);
        register("user-list-2", "evt-b", NotificationChannel.IN_APP);

        Page<Notification> emailResults = notificationQueryService.list(
                "user-list-2", null, NotificationChannel.EMAIL, null, PageRequest.of(0, 10));

        assertThat(emailResults.getContent()).extracting(Notification::getId).containsExactly(email.getId());
    }

    @Test
    void markRead_calledMultipleTimes_isSafeAndKeepsFirstReadAt() {
        Notification notification = register("user-1", "evt-read-twice", NotificationChannel.EMAIL);

        Notification first = notificationQueryService.markRead(notification.getId());
        Instant firstReadAt = first.getReadAt();

        Notification second = notificationQueryService.markRead(notification.getId());

        assertThat(firstReadAt).isNotNull();
        assertThat(second.getReadAt()).isEqualTo(firstReadAt);
    }
}
