package com.example.notification.infrastructure.persistence;

import com.example.notification.domain.NotificationAttempt;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, Long> {

    List<NotificationAttempt> findByNotificationIdOrderByAttemptNoAsc(UUID notificationId);
}
