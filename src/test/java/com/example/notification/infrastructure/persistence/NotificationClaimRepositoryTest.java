package com.example.notification.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationStatus;
import com.example.notification.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@code UPDATE ... RETURNING} 기반 claim()이 실제로 PROCESSING 전이에 성공한 row만
 * 반환하는지, 그리고 여러 worker가 동시에 claim해도 같은 row가 중복 반환되지 않는지 검증한다.
 */
class NotificationClaimRepositoryTest extends AbstractIntegrationTest {

    @Test
    void claim_returnsOnlyRowsActuallyTransitionedToProcessing() {
        Notification n1 = register("user-1", "evt-claim-1", NotificationChannel.EMAIL);
        Notification n2 = register("user-1", "evt-claim-2", NotificationChannel.EMAIL);

        Instant now = clock.instant();
        List<UUID> claimed =
                notificationClaimRepository.claim("worker-test", now, now.plusSeconds(30), 10);

        assertThat(claimed).containsExactlyInAnyOrder(n1.getId(), n2.getId());
        List<Notification> reloaded = notificationRepository.findAllById(claimed);
        assertThat(reloaded).hasSize(2);
        assertThat(reloaded).allMatch(n -> n.getStatus() == NotificationStatus.PROCESSING);
        assertThat(reloaded).allMatch(n -> "worker-test".equals(n.getWorkerId()));
    }

    @Test
    void claim_doesNotReturnNotYetDueNotifications() {
        Notification due = register("user-1", "evt-claim-due", NotificationChannel.EMAIL);
        Notification future = register("user-1", "evt-claim-future", NotificationChannel.EMAIL);
        forceNextAttemptAt(future.getId(), clock.instant().plusSeconds(3600));

        Instant now = clock.instant();
        List<UUID> claimed =
                notificationClaimRepository.claim("worker-test", now, now.plusSeconds(30), 10);

        assertThat(claimed).containsExactly(due.getId());
        Notification futureReloaded =
                notificationRepository.findById(future.getId()).orElseThrow();
        assertThat(futureReloaded.getStatus()).isEqualTo(NotificationStatus.READY);
    }

    @Test
    void concurrentClaim_fromMultipleWorkers_neverReturnsSameIdTwice() throws InterruptedException {
        int notificationCount = 20;
        List<UUID> registeredIds = new ArrayList<>();
        for (int i = 0; i < notificationCount; i++) {
            registeredIds.add(register("user-1", "evt-claim-concurrent-" + i, NotificationChannel.EMAIL)
                    .getId());
        }

        int workerCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workerCount);
        List<UUID> allClaimedIds = new CopyOnWriteArrayList<>();

        for (int i = 0; i < workerCount; i++) {
            String workerId = "worker-" + i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    Instant now = clock.instant();
                    List<UUID> claimed = notificationClaimRepository.claim(
                            workerId, now, now.plusSeconds(30), notificationCount);
                    allClaimedIds.addAll(claimed);
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
        // 중복 없이 정확히 등록한 만큼만 claim되어야 한다(같은 id가 두 worker에게 동시에 반환되면 안 됨).
        Set<UUID> uniqueClaimedIds = ConcurrentHashMap.newKeySet();
        uniqueClaimedIds.addAll(allClaimedIds);
        assertThat(allClaimedIds).hasSize(notificationCount);
        assertThat(uniqueClaimedIds).hasSize(notificationCount);
        assertThat(uniqueClaimedIds).containsExactlyInAnyOrderElementsOf(registeredIds);

        List<Notification> reloaded = notificationRepository.findAllById(registeredIds);
        assertThat(reloaded).allMatch(n -> n.getStatus() == NotificationStatus.PROCESSING);
    }
}
