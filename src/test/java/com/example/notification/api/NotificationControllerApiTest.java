package com.example.notification.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.notification.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.client.TestRestTemplate;

/** 서비스 계층 테스트와 별개로 HTTP 계층(JSON 직렬화, 라우팅, 예외 매핑)이 실제로 동작하는지 확인하는 end-to-end 스모크 테스트. */
class NotificationControllerApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @SuppressWarnings("unchecked")
    void fullLifecycle_registerGetListReadRetry() {
        Map<String, Object> requestBody = Map.of(
                "recipientId", "user-api",
                "notificationType", "ENROLLMENT_COMPLETED",
                "eventId", "evt-api-1",
                "referenceId", "course-1",
                "channel", "EMAIL",
                "payload", Map.of("courseTitle", "Spring Boot 입문"));

        ResponseEntity<Map> registerResponse = restTemplate.postForEntity("/api/notifications", requestBody, Map.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> registered = (Map<String, Object>) registerResponse.getBody().get("data");
        String notificationId = (String) registered.get("notificationId");
        assertThat(registered.get("status")).isEqualTo("READY");
        assertThat(registered.get("title")).isEqualTo("수강 신청이 완료되었습니다.");

        // 동일 요청 재호출 -> 같은 notificationId 반환
        ResponseEntity<Map> duplicateResponse =
                restTemplate.postForEntity("/api/notifications", requestBody, Map.class);
        Map<String, Object> duplicate = (Map<String, Object>) duplicateResponse.getBody().get("data");
        assertThat(duplicate.get("notificationId")).isEqualTo(notificationId);

        // 상태 조회
        ResponseEntity<Map> getResponse =
                restTemplate.getForEntity("/api/notifications/{id}", Map.class, notificationId);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 목록 조회
        ResponseEntity<Map> listResponse = restTemplate.getForEntity(
                "/api/users/{recipientId}/notifications", Map.class, "user-api");
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> listData = (Map<String, Object>) listResponse.getBody().get("data");
        assertThat((Integer) listData.get("totalElements")).isGreaterThanOrEqualTo(1);

        // 읽음 처리(멱등) - 두 번 호출해도 모두 200
        ResponseEntity<Map> readResponse = restTemplate.exchange(
                "/api/notifications/{id}/read", HttpMethod.PATCH, null, Map.class, notificationId);
        assertThat(readResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> readAgainResponse = restTemplate.exchange(
                "/api/notifications/{id}/read", HttpMethod.PATCH, null, Map.class, notificationId);
        assertThat(readAgainResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // SENT/READY 상태에서 수동 재시도 요청 -> 409 CONFLICT
        ResponseEntity<Map> retryResponse =
                restTemplate.postForEntity("/api/notifications/{id}/retry", null, Map.class, notificationId);
        assertThat(retryResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void get_withUnknownId_returns404() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/notifications/{id}", Map.class, "00000000-0000-0000-0000-000000000000");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void register_withMissingRequiredField_returns400() {
        Map<String, Object> invalidBody = Map.of(
                "notificationType", "ENROLLMENT_COMPLETED",
                "eventId", "evt-invalid",
                "channel", "EMAIL");

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/notifications", invalidBody, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void register_withFutureScheduledAt_staysReadyWithScheduledAtEchoedBack() {
        Instant future = Instant.now().plusSeconds(3600);
        Map<String, Object> requestBody = Map.of(
                "recipientId", "user-api-scheduled",
                "notificationType", "COURSE_START_D1",
                "eventId", "evt-api-scheduled",
                "referenceId", "course-1",
                "channel", "EMAIL",
                "payload", Map.of("courseTitle", "Spring Boot 입문"),
                "scheduledAt", future.toString());

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/notifications", requestBody, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("READY");
        assertThat(Instant.parse((String) data.get("scheduledAt"))).isEqualTo(future);
    }

    @Test
    void register_withPastScheduledAt_returns400() {
        Instant past = Instant.now().minusSeconds(60);
        Map<String, Object> requestBody = Map.of(
                "recipientId", "user-api-scheduled-invalid",
                "notificationType", "COURSE_START_D1",
                "eventId", "evt-api-scheduled-invalid",
                "channel", "EMAIL",
                "scheduledAt", past.toString());

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/notifications", requestBody, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
