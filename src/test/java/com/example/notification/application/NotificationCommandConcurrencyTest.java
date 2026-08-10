package com.example.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationType;
import com.example.notification.support.AbstractIntegrationTest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 동일한 알림 요청이 동시에 여러 번 들어와도 notification row가 1개만 생성되는지 검증한다. */
class NotificationCommandConcurrencyTest extends AbstractIntegrationTest {

    @Test
    void concurrentRegisterRequestsWithSameIdempotencyKey_createOnlyOneRow() throws InterruptedException {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        Set<UUID> resultIds = ConcurrentHashMap.newKeySet();

        NotificationCommandService.RegisterCommand command = new NotificationCommandService.RegisterCommand(
                "user-concurrent",
                NotificationType.PAYMENT_CONFIRMED,
                "evt-concurrent",
                "course-1",
                NotificationChannel.EMAIL,
                Map.of("courseTitle", "Spring Boot 입문"));

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    Notification notification = notificationCommandService.register(command);
                    resultIds.add(notification.getId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(resultIds).hasSize(1);
        assertThat(notificationRepository.count()).isEqualTo(1);
    }
}
