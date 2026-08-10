package com.example.notification.domain;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 타입별 title/message 생성기.
 * 복잡한 템플릿 엔진 대신 enum switch 기반으로 단순하게 구현.
 * 추후 DB 기반 템플릿 또는 외부 템플릿 엔진으로 교체 가능하도록 인터페이스 형태를 유지한다.
 */
@Component
public class NotificationTemplateProvider {

    public RenderedTemplate render(NotificationType type, Map<String, Object> payload) {
        String courseTitle = payload != null && payload.get("courseTitle") != null
                ? String.valueOf(payload.get("courseTitle"))
                : "";

        return switch (type) {
            case ENROLLMENT_COMPLETED -> new RenderedTemplate(
                    "수강 신청이 완료되었습니다.",
                    "%s 수강 신청이 완료되었습니다.".formatted(courseTitle).trim());
            case PAYMENT_CONFIRMED -> new RenderedTemplate(
                    "결제가 확정되었습니다.",
                    "%s 결제가 확정되었습니다.".formatted(courseTitle).trim());
            case COURSE_START_D1 -> new RenderedTemplate(
                    "강의 시작 하루 전입니다.",
                    "%s 강의가 내일 시작됩니다.".formatted(courseTitle).trim());
            case ENROLLMENT_CANCELLED -> new RenderedTemplate(
                    "수강 신청이 취소되었습니다.",
                    "%s 수강 신청이 취소되었습니다.".formatted(courseTitle).trim());
        };
    }

    public record RenderedTemplate(String title, String message) {
    }
}
