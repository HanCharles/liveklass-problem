package com.example.notification.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * worker가 발송 가능한 알림을 claim하는 저장소.
 *
 * PostgreSQL {@code FOR UPDATE SKIP LOCKED}로 여러 인스턴스가 동시에 같은 row를 잡지 않도록 하고,
 * 잡은 row만 상태 조건부(READY/RETRY_WAITING)로 PROCESSING 전이시킨다.
 *
 * READY 상태도 RETRY_WAITING과 동일하게 {@code next_attempt_at <= now} 조건을 검사한다.
 * 즉시 발송 요청은 등록 시 {@code next_attempt_at = 등록 시각}으로 저장되므로 이 조건이 항상
 * 참이라 기존 동작과 동일하고, 예약 발송 요청(선택 구현)은 {@code next_attempt_at = 예약 시각}
 * 으로 저장되어 그 시각이 지나기 전까지는 자연스럽게 claim되지 않는다 — 별도의 상태나 claim
 * 로직 분기 없이 기존 구조를 그대로 재사용한다.
 *
 * claim 자체는 짧은 별도 트랜잭션(REQUIRES_NEW)으로 즉시 커밋하여, 이후 실제 sender 호출이
 * 이 트랜잭션(따라서 DB 커넥션/락)을 오래 붙잡지 않도록 한다.
 */
@Repository
public class NotificationClaimRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> claim(String workerId, Instant now, Instant leaseUntil, int batchSize) {
        @SuppressWarnings("unchecked")
        List<UUID> candidateIds = entityManager
                .createNativeQuery(
                        """
                        SELECT id FROM notification
                        WHERE status IN ('READY', 'RETRY_WAITING')
                          AND next_attempt_at <= :now
                        ORDER BY created_at
                        FOR UPDATE SKIP LOCKED
                        LIMIT :batchSize
                        """)
                .setParameter("now", now)
                .setParameter("batchSize", batchSize)
                .getResultList();

        if (candidateIds.isEmpty()) {
            return candidateIds;
        }

        entityManager
                .createNativeQuery(
                        """
                        UPDATE notification
                        SET status = 'PROCESSING',
                            worker_id = :workerId,
                            processing_started_at = :now,
                            lease_until = :leaseUntil,
                            updated_at = :now
                        WHERE id IN (:ids)
                          AND status IN ('READY', 'RETRY_WAITING')
                        """)
                .setParameter("workerId", workerId)
                .setParameter("now", now)
                .setParameter("leaseUntil", leaseUntil)
                .setParameter("ids", candidateIds)
                .executeUpdate();

        return candidateIds;
    }

    /**
     * PROCESSING 상태인데 leaseUntil이 지난(stuck) 알림을 READY로 복구한다.
     * attemptCount는 증가시키지 않는다(단순 stuck 복구는 발송 실패로 보지 않음).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverStuck(Instant now) {
        return entityManager
                .createNativeQuery(
                        """
                        UPDATE notification
                        SET status = 'READY',
                            worker_id = NULL,
                            processing_started_at = NULL,
                            lease_until = NULL,
                            last_failure_reason = 'processing lease expired',
                            updated_at = :now
                        WHERE status = 'PROCESSING'
                          AND lease_until < :now
                        """)
                .setParameter("now", now)
                .executeUpdate();
    }
}
