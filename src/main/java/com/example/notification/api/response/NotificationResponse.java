package com.example.notification.api.response;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationStatus;
import com.example.notification.domain.NotificationType;
import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID notificationId,
        String recipientId,
        NotificationType notificationType,
        String eventId,
        String referenceId,
        NotificationChannel channel,
        NotificationStatus status,
        String title,
        String message,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        String lastFailureReason,
        Instant sentAt,
        Instant readAt,
        Instant createdAt,
        Instant updatedAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getRecipientId(),
                notification.getNotificationType(),
                notification.getEventId(),
                notification.getReferenceId(),
                notification.getChannel(),
                notification.getStatus(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getAttemptCount(),
                notification.getMaxAttempts(),
                notification.getNextAttemptAt(),
                notification.getLastFailureReason(),
                notification.getSentAt(),
                notification.getReadAt(),
                notification.getCreatedAt(),
                notification.getUpdatedAt());
    }
}
