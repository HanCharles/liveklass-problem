package com.example.notification.infrastructure.sender;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 실제 이메일 발송 대신 로그로 대체하는 Mock sender.
 *
 * 과제 제약상 실제 이메일 발송은 불필요하므로 로그 출력으로 대체한다.
 * 테스트에서 결정적으로 실패/성공 시나리오를 재현할 수 있도록 eventId 기준으로
 * "N번 실패 후 성공" / "항상 실패" 동작을 설정할 수 있다.
 */
@Component
public class MockEmailSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(MockEmailSender.class);

    private final ConcurrentHashMap<String, AtomicInteger> remainingFailuresByEventId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> alwaysFailByEventId = new ConcurrentHashMap<>();

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void send(Notification notification) {
        String eventId = notification.getEventId();

        if (Boolean.TRUE.equals(alwaysFailByEventId.get(eventId))) {
            throw new NotificationSendException("mock email server error (configured always-fail) for eventId=" + eventId);
        }

        AtomicInteger remaining = remainingFailuresByEventId.get(eventId);
        if (remaining != null && remaining.get() > 0) {
            remaining.decrementAndGet();
            throw new NotificationSendException("mock email server error (transient) for eventId=" + eventId);
        }

        log.info(
                "[MockEmailSender] EMAIL sent. recipientId={}, title={}, message={}",
                notification.getRecipientId(),
                notification.getTitle(),
                notification.getMessage());
    }

    /** 이후 send() 호출에서 지정한 eventId가 처음 {@code times}번은 실패하고 그 다음부터 성공하도록 설정한다. */
    public void failNext(String eventId, int times) {
        remainingFailuresByEventId.put(eventId, new AtomicInteger(times));
    }

    /** 지정한 eventId에 대해 항상 실패하도록 설정한다(최종 FAILED 도달 테스트용). */
    public void alwaysFail(String eventId) {
        alwaysFailByEventId.put(eventId, Boolean.TRUE);
    }

    /** 테스트 간 상태 격리를 위한 초기화. */
    public void reset() {
        remainingFailuresByEventId.clear();
        alwaysFailByEventId.clear();
    }
}
