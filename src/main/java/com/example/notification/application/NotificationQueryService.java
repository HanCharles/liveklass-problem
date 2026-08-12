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

    /**
     * 이미 읽은 알림을 다시 읽음 처리해도 성공해야 하는 멱등 API.
     *
     * {@code readAt}이 없을 때만 세팅하는 조건부 UPDATE(SQL {@code COALESCE})를 하나의
     * 원자적 문장으로 실행하므로, 여러 기기에서 정말로 동시에 요청이 들어와도(단순 낙관적
     * read-modify-write가 아니라 DB row lock으로 직렬화됨) 가장 먼저 커밋된 시각만 최종
     * readAt으로 남는다(lost update 불가능).
     */
    @Transactional
    public Notification markRead(UUID id) {
        notificationRepository.markReadIfUnread(id, clock.instant());
        return getById(id);
    }
}
