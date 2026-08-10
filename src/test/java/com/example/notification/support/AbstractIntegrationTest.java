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
import com.example.notification.infrastructure.worker.StuckNotificationRecoveryWorker;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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
    protected Clock clock;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetMockSenderState() {
        mockEmailSender.reset();
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
