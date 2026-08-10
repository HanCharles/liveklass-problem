package com.example.notification.infrastructure.sender;

/** 알림 발송 실패를 나타내는 예외. NotificationProcessor가 잡아서 재시도 정책에 따라 처리한다. */
public class NotificationSendException extends RuntimeException {

    public NotificationSendException(String message) {
        super(message);
    }

    public NotificationSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
