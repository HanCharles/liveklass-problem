package com.example.notification.api.response;

import com.example.notification.domain.Notification;
import java.util.List;
import org.springframework.data.domain.Page;

public record NotificationListResponse(
        List<NotificationResponse> items, int page, int size, long totalElements, int totalPages) {

    public static NotificationListResponse from(Page<Notification> pageResult) {
        List<NotificationResponse> items =
                pageResult.getContent().stream().map(NotificationResponse::from).toList();
        return new NotificationListResponse(
                items, pageResult.getNumber(), pageResult.getSize(), pageResult.getTotalElements(), pageResult.getTotalPages());
    }
}
