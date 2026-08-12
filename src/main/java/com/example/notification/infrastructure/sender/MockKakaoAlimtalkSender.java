package com.example.notification.infrastructure.sender;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 카카오 알림톡(비즈메시지) 발송을 흉내내는 Mock sender.
 *
 * 실제 구현에서는 카카오 비즈메시지 REST API를 호출하는 클라이언트로 교체하면 되고,
 * {@link NotificationSender} 인터페이스를 구현하는 것 외에는 {@link NotificationProcessor}를
 * 포함한 다른 코드를 전혀 수정할 필요가 없다(채널별 sender는 스프링 컨텍스트에 등록된
 * {@code List<NotificationSender>}에서 channel()로 자동 매핑됨).
 *
 * {@link MockEmailSender}와 동일하게 eventId 기준으로 "N번 실패 후 성공" / "항상 실패"를
 * 테스트에서 결정적으로 재현할 수 있다.
 */
@Component
public class MockKakaoAlimtalkSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(MockKakaoAlimtalkSender.class);

    private final ConcurrentHashMap<String, AtomicInteger> remainingFailuresByEventId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> alwaysFailByEventId = new ConcurrentHashMap<>();

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.KAKAO_ALIMTALK;
    }

    @Override
    public void send(Notification notification) {
        String eventId = notification.getEventId();

        if (Boolean.TRUE.equals(alwaysFailByEventId.get(eventId))) {
            throw new NotificationSendException(
                    "mock kakao alimtalk API error (configured always-fail) for eventId=" + eventId);
        }

        AtomicInteger remaining = remainingFailuresByEventId.get(eventId);
        if (remaining != null && remaining.get() > 0) {
            remaining.decrementAndGet();
            throw new NotificationSendException("mock kakao alimtalk API error (transient) for eventId=" + eventId);
        }

        log.info(
                "[MockKakaoAlimtalkSender] KAKAO_ALIMTALK sent. recipientId={}, title={}, message={}",
                notification.getRecipientId(),
                notification.getTitle(),
                notification.getMessage());
    }

    /** 이후 send() 호출에서 지정한 eventId가 처음 {@code times}번은 실패하고 그 다음부터 성공하도록 설정한다. */
    public void failNext(String eventId, int times) {
        remainingFailuresByEventId.put(eventId, new AtomicInteger(times));
    }

    /** 지정한 eventId에 대해 항상 실패하도록 설정한다. */
    public void alwaysFail(String eventId) {
        alwaysFailByEventId.put(eventId, Boolean.TRUE);
    }

    /** 테스트 간 상태 격리를 위한 초기화. */
    public void reset() {
        remainingFailuresByEventId.clear();
        alwaysFailByEventId.clear();
    }
}
