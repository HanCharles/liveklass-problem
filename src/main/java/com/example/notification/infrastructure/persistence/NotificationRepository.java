package com.example.notification.infrastructure.persistence;

import com.example.notification.domain.Notification;
import com.example.notification.domain.NotificationChannel;
import com.example.notification.domain.NotificationStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    /**
     * 읽음 처리를 원자적 조건부 UPDATE로 수행한다: {@code readAt}이 이미 있으면 그 값을 그대로
     * 유지하고(COALESCE), 없을 때만 {@code now}로 설정한다. 여러 기기에서 정말로 동시에 요청이
     * 와도(Read-Modify-Write가 아니라 DB가 이 UPDATE문 실행 중 해당 row를 잠그므로) 항상
     * 가장 먼저 커밋된 시각만 최종 readAt으로 남는 것을 보장한다(lost update 불가능).
     * {@code clearAutomatically = true}로 영속성 컨텍스트를 비워, 이후 재조회 시 이 UPDATE
     * 결과가 반드시 반영된 최신 상태를 읽도록 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = COALESCE(n.readAt, :now), n.updatedAt = :now WHERE n.id = :id")
    int markReadIfUnread(@Param("id") UUID id, @Param("now") Instant now);

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
