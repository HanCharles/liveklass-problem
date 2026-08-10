package com.example.notification.infrastructure.worker;

import com.example.notification.application.NotificationProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 발송 worker의 스케줄링 진입점. 실제 로직은 {@link NotificationProcessor}에 위임한다.
 * 실제 메시지 브로커 대신 {@code @Scheduled} polling으로 구현했으며, 운영 환경에서는 이
 * {@code @Scheduled} 트리거를 Kafka/SQS 컨슈머로 교체하는 것을 상정한다.
 *
 * 빈 자체는 항상 생성되어 테스트에서 {@link NotificationProcessor#processOnce()}를 직접
 * 호출해 결정적으로 검증할 수 있도록 하고, {@code notification.worker.enabled=false}일 때는
 * 스케줄 트리거만 아무 동작도 하지 않도록 한다.
 */
@Component
public class NotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);

    private final NotificationProcessor processor;
    private final boolean enabled;

    public NotificationWorker(
            NotificationProcessor processor, @Value("${notification.worker.enabled}") boolean enabled) {
        this.processor = processor;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${notification.worker.poll-interval-ms}")
    public void scheduledPoll() {
        if (!enabled) {
            return;
        }
        int processed = processor.processOnce();
        if (processed > 0) {
            log.info("processed {} notification(s)", processed);
        }
    }
}
