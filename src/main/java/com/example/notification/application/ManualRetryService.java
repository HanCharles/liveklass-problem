package com.example.notification.application;

import com.example.notification.common.ApiException;
import com.example.notification.common.ErrorCode;
import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationStatus;
import com.example.notification.infrastructure.persistence.NotificationRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최대 재시도 초과로 FAILED 된 알림을 운영자가 수동으로 재시도시키는 서비스.
 * 기본 정책: attemptCount=0으로 초기화하고 READY로 되돌린다(재시도 횟수를 다시 소진할 수 있게 함).
 */
@Service
@RequiredArgsConstructor
public class ManualRetryService {

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    @Transactional
    public Notification retry(UUID id) {
        Notification notification = notificationRepository
                .findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOTIFICATION_NOT_FOUND));

        if (notification.getStatus() != NotificationStatus.FAILED) {
            throw new ApiException(
                    ErrorCode.INVALID_RETRY_STATE,
                    "FAILED 상태에서만 수동 재시도할 수 있습니다. 현재 상태: " + notification.getStatus());
        }

        notification.retryManually(clock.instant());
        return notification;
    }
}
