package com.example.notification.infrastructure.sender;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 인앱 알림은 별도 외부 연동 없이 저장/로그로 대체한다(실제 발송 성공으로 간주). */
@Component
public class InAppNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(InAppNotificationSender.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public void send(Notification notification) {
        log.info(
                "[InAppNotificationSender] IN_APP delivered. recipientId={}, title={}, message={}",
                notification.getRecipientId(),
                notification.getTitle(),
                notification.getMessage());
    }
}
