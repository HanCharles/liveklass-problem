package com.example.notification.infrastructure.persistence;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            SELECT n FROM Notification n
            WHERE n.recipientId = :recipientId
              AND (:read IS NULL OR (:read = TRUE AND n.readAt IS NOT NULL) OR (:read = FALSE AND n.readAt IS NULL))
              AND (:channel IS NULL OR n.channel = :channel)
              AND (:status IS NULL OR n.status = :status)
            ORDER BY n.createdAt DESC
            """)
    Page<Notification> search(
            @Param("recipientId") String recipientId,
            @Param("read") Boolean read,
            @Param("channel") NotificationChannel channel,
            @Param("status") NotificationStatus status,
            Pageable pageable);
}
