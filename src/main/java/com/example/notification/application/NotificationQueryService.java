package com.example.notification.application;

import com.example.notification.common.ApiException;
import com.example.notification.common.ErrorCode;
import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationStatus;
import com.example.notification.infrastructure.persistence.NotificationRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    public Notification getById(UUID id) {
        return notificationRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOTIFICATION_NOT_FOUND));
    }

    public Page<Notification> list(
            String recipientId, Boolean read, NotificationChannel channel, NotificationStatus status, Pageable pageable) {
        return notificationRepository.search(recipientId, read, channel, status, pageable);
    }

    /** 이미 읽은 알림을 다시 읽음 처리해도 성공해야 하는 멱등 API. 여러 기기에서 동시에 호출되어도 readAt은 최초 1회만 반영된다. */
    @Transactional
    public Notification markRead(UUID id) {
        Notification notification = getById(id);
        notification.markRead(clock.instant());
        return notification;
    }
}
