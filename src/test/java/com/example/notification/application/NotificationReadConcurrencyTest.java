package com.example.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 여러 기기에서 정말로 동시에 읽음 처리(PATCH /read) 요청이 들어와도 readAt이 단 하나의
 * 값으로만 수렴하는지(lost update가 없는지) 검증한다.
 *
 * {@code NotificationRepository.markReadIfUnread}가 {@code UPDATE ... SET read_at =
 * COALESCE(read_at, :now)}를 하나의 원자적 문장으로 실행하므로, Postgres가 해당 row에 대해
 * UPDATE 실행 동안 잡는 row lock으로 동시 요청들이 직렬화되어 가장 먼저 커밋된 시각만
 * 최종적으로 남는다.
 */
class NotificationReadConcurrencyTest extends AbstractIntegrationTest {

    @Test
    void concurrentMarkReadRequests_convergeToSingleReadAt() throws InterruptedException {
        Notification notification =
                register("user-read-concurrent", "evt-read-concurrent", NotificationChannel.EMAIL);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        Set<Instant> observedReadAts = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    Notification reloaded = notificationQueryService.markRead(notification.getId());
                    observedReadAts.add(reloaded.getReadAt());
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
        assertThat(observedReadAts).hasSize(1);

        Notification reloaded =
                notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(reloaded.getReadAt()).isEqualTo(observedReadAts.iterator().next());
    }
}
