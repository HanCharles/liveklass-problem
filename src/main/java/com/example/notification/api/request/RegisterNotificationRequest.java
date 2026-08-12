package com.example.notification.api.request;

import com.example.notification.application.NotificationCommandService;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationType;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;

public record RegisterNotificationRequest(
        @NotBlank String recipientId,
        @NotNull NotificationType notificationType,
        @NotBlank String eventId,
        String referenceId,
        @NotNull NotificationChannel channel,
        Map<String, Object> payload,
        @Future Instant scheduledAt) {

    public NotificationCommandService.RegisterCommand toCommand() {
        return new NotificationCommandService.RegisterCommand(
                recipientId, notificationType, eventId, referenceId, channel, payload, scheduledAt);
    }
}
