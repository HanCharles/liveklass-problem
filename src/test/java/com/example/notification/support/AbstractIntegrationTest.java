package com.example.notification.support;

import com.example.notification.application.ManualRetryService;
import com.example.notification.application.NotificationCommandService;
import com.example.notification.application.NotificationProcessor;
import com.example.notification.application.NotificationQueryService;
import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationType;
import com.example.notification.infrastructure.persistence.NotificationAttemptRepository;
import com.example.notification.infrastructure.persistence.NotificationClaimRepository;
import com.example.notification.infrastructure.persistence.NotificationRepository;
import com.example.notification.infrastructure.sender.MockEmailSender;
import com.example.notification.infrastructure.sender.MockKakaoAlimtalkSender;
import com.example.notification.infrastructure.worker.StuckNotificationRecoveryWorker;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 PostgreSQL(Testcontainers)을 사용하는 통합 테스트 베이스.
 *
 * H2 대신 실제 Postgres를 써야 {@code FOR UPDATE SKIP LOCKED}, unique constraint, row lock 등
 * 이 과제의 핵심인 동시성 제어 로직을 있는 그대로 검증할 수 있다.
 *
 * 컨테이너는 JVM당 1개만 기동해(싱글턴 패턴) 테스트 클래스마다 재기동하지 않도록 한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("notification")
            .withUsername("notification")
            .withPassword("notification");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected NotificationRepository notificationRepository;

    @Autowired
    protected NotificationAttemptRepository notificationAttemptRepository;

    @Autowired
    protected NotificationClaimRepository notificationClaimRepository;

    @Autowired
    protected NotificationCommandService notificationCommandService;

    @Autowired
    protected NotificationQueryService notificationQueryService;

    @Autowired
    protected ManualRetryService manualRetryService;

    @Autowired
    protected NotificationProcessor notificationProcessor;

    @Autowired
    protected StuckNotificationRecoveryWorker stuckNotificationRecoveryWorker;

    @Autowired
    protected MockEmailSender mockEmailSender;

    @Autowired
    protected MockKakaoAlimtalkSender mockKakaoAlimtalkSender;

    @Autowired
    protected Clock clock;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetMockSenderState() {
        mockEmailSender.reset();
        mockKakaoAlimtalkSender.reset();
        notificationProcessor.resetCircuitBreakers();
        notificationAttemptRepository.deleteAll();
        notificationRepository.deleteAll();
    }

    /**
     * 테스트가 RETRY_WAITING처럼 nextAttemptAt이 가까운 미래인 row를 남긴 채 끝나면,
     * 그 다음에 실행되는 테스트 클래스가 (예: SchedulerWiringTest처럼 다른 설정이라
     * 새 Spring context를 부트스트랩해야 하는 경우) 그 부트스트랩 시간 동안 실시간
     * 스케줄러가 이 leftover row를 먼저 집어가 버릴 수 있다 — 이때 그 클래스의
     * {@code @BeforeEach}가 아직 실행되기 전이라 레이스 컨디션(FK 위반)이 발생할 수 있으므로,
     * 매 테스트 종료 시점에도 명시적으로 정리해 다음 컨텍스트 부트스트랩 시점에 DB가
     * 비어있도록 보장한다.
     */
    @AfterEach
    void cleanupAfterTest() {
        notificationAttemptRepository.deleteAll();
        notificationRepository.deleteAll();
    }

    protected Notification register(String recipientId, String eventId, NotificationChannel channel) {
        return notificationCommandService.register(new NotificationCommandService.RegisterCommand(
                recipientId,
                NotificationType.ENROLLMENT_COMPLETED,
                eventId,
                "course-1",
                channel,
                Map.of("courseTitle", "Spring Boot 입문")));
    }

    protected void forceNextAttemptAt(UUID notificationId, Instant instant) {
        jdbcTemplate.update(
                "UPDATE notification SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(instant),
                notificationId);
    }

    protected void forceProcessing(UUID notificationId, String workerId, Instant processingStartedAt, Instant leaseUntil) {
        jdbcTemplate.update(
                "UPDATE notification SET status = 'PROCESSING', worker_id = ?, processing_started_at = ?, lease_until = ? WHERE id = ?",
                workerId,
                Timestamp.from(processingStartedAt),
                Timestamp.from(leaseUntil),
                notificationId);
    }
}
