package com.example.notification.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationStatus;
import com.example.notification.support.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 나머지 테스트는 processOnce()/recoverOnce()를 직접 호출해 스케줄러 타이밍에 의존하지 않지만,
 * 이 테스트만은 {@code @Scheduled} 배선 자체가 실제로 동작하는지 증명하기 위해 짧은 polling 주기로
 * 스케줄러를 켜고 Awaitility로 비동기 처리 결과를 기다린다.
 *
 * {@code @DirtiesContext}로 테스트 종료 후 이 컨텍스트를 닫아 백그라운드 스케줄러 스레드를
 * 반드시 종료시킨다. 그렇지 않으면 Spring의 컨텍스트 캐싱 때문에 이후 다른 테스트가 공유
 * Testcontainers DB를 초기화(deleteAll)하는 도중에도 이 스케줄러가 계속 폴링하며 row를
 * 새로 만들어 FK 위반 등 예측 불가능한 간섭을 일으킬 수 있다.
 */
@TestPropertySource(
        properties = {
            "notification.worker.enabled=true",
            "notification.worker.poll-interval-ms=300",
            "notification.recovery.enabled=true",
            "notification.recovery.interval-ms=300"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SchedulerWiringTest extends AbstractIntegrationTest {

    @Test
    void scheduledWorkerAutomaticallyProcessesReadyNotification() {
        Notification notification = register("user-scheduler", "evt-scheduler", NotificationChannel.EMAIL);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Notification reloaded =
                    notificationRepository.findById(notification.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
        });
    }
}
