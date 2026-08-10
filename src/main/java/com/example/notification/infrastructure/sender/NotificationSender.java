package com.example.notification.infrastructure.sender;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;

/**
 * 채널별 알림 발송기. 실제 운영 환경에서는 SMTP 클라이언트, 푸시 서비스 등으로 교체 가능하도록
 * 인터페이스로 분리했다.
 */
public interface NotificationSender {

    NotificationChannel channel();

    /** 발송에 실패하면 {@link NotificationSendException}을 던진다. */
    void send(Notification notification);
}
