package com.example.notification.api;

import com.example.notification.api.request.RegisterNotificationRequest;
import com.example.notification.api.response.NotificationListResponse;
import com.example.notification.api.response.NotificationResponse;
import com.example.notification.application.ManualRetryService;
import com.example.notification.application.NotificationCommandService;
import com.example.notification.application.NotificationQueryService;
import com.example.notification.common.ApiResponse;
import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationStatus;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationCommandService commandService;
    private final NotificationQueryService queryService;
    private final ManualRetryService manualRetryService;

    /** 알림 발송 요청 등록. 즉시 발송이 아니라 비동기 처리 대상 등록이며, 동일 idempotency key 요청이면 기존 알림을 반환한다. */
    @PostMapping("/api/notifications")
    public ResponseEntity<ApiResponse<NotificationResponse>> register(
            @Valid @RequestBody RegisterNotificationRequest request) {
        Notification notification = commandService.register(request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(NotificationResponse.from(notification)));
    }

    /** 특정 알림 요청의 현재 상태 조회. */
    @GetMapping("/api/notifications/{id}")
    public ResponseEntity<ApiResponse<NotificationResponse>> get(@PathVariable UUID id) {
        Notification notification = queryService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(NotificationResponse.from(notification)));
    }

    /** 수신자 기준 알림 목록 조회. 읽음/안읽음, 채널, 상태 필터를 지원한다. */
    @GetMapping("/api/users/{recipientId}/notifications")
    public ResponseEntity<ApiResponse<NotificationListResponse>> list(
            @PathVariable String recipientId,
            @RequestParam(required = false) Boolean read,
            @RequestParam(required = false) NotificationChannel channel,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Notification> result =
                queryService.list(recipientId, read, channel, status, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(NotificationListResponse.from(result)));
    }

    /** 읽음 처리. readAt이 이미 있으면 갱신하지 않고 그대로 성공 응답한다(멱등). */
    @PatchMapping("/api/notifications/{id}/read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markRead(@PathVariable UUID id) {
        Notification notification = queryService.markRead(id);
        return ResponseEntity.ok(ApiResponse.ok(NotificationResponse.from(notification)));
    }

    /** FAILED 상태 알림을 운영자가 수동으로 재시도(READY로 되돌림)시킨다. */
    @PostMapping("/api/notifications/{id}/retry")
    public ResponseEntity<ApiResponse<NotificationResponse>> retry(@PathVariable UUID id) {
        Notification notification = manualRetryService.retry(id);
        return ResponseEntity.ok(ApiResponse.ok(NotificationResponse.from(notification)));
    }
}
