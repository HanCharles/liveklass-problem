package com.example.notification.application;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationTemplateProvider;
import com.example.notification.domain.NotificationType;
import com.example.notification.domain.RetryPolicy;
import com.example.notification.infrastructure.persistence.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 알림 발송 요청 등록(접수) 서비스.
 *
 * 동일 idempotency key(recipientId+notificationType+eventId+channel) 요청이 이미 있으면
 * 새로 만들지 않고 기존 row를 반환한다. 동시에 같은 요청이 들어오는 경우에는 DB unique
 * constraint 위반을 잡아 재조회하는 방식으로 최종 방어한다(row는 항상 1개만 생성됨).
 */
@Service
@RequiredArgsConstructor
public class NotificationCommandService {

    private final NotificationRepository notificationRepository;
    private final NotificationTemplateProvider templateProvider;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    public Notification register(RegisterCommand command) {
        String idempotencyKey = Notification.buildIdempotencyKey(
                command.recipientId(), command.notificationType(), command.eventId(), command.channel());

        Optional<Notification> existing = notificationRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        NotificationTemplateProvider.RenderedTemplate rendered =
                templateProvider.render(command.notificationType(), command.payload());

        Notification notification = Notification.create(
                command.recipientId(),
                command.notificationType(),
                command.eventId(),
                command.referenceId(),
                command.channel(),
                rendered.title(),
                rendered.message(),
                command.payload(),
                command.scheduledAt(),
                retryPolicy.maxAttempts(),
                clock.instant());

        try {
            return notificationRepository.saveAndFlush(notification);
        } catch (DataIntegrityViolationException e) {
            return notificationRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
        }
    }

    public record RegisterCommand(
            String recipientId,
            NotificationType notificationType,
            String eventId,
            String referenceId,
            NotificationChannel channel,
            Map<String, Object> payload,
            Instant scheduledAt) {

        /** 예약 발송이 필요 없는(즉시 처리) 등록 요청을 위한 편의 생성자. */
        public RegisterCommand(
                String recipientId,
                NotificationType notificationType,
                String eventId,
                String referenceId,
                NotificationChannel channel,
                Map<String, Object> payload) {
            this(recipientId, notificationType, eventId, referenceId, channel, payload, null);
        }
    }
}
