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
 * {@code UPDATE ... RETURNING}으로 실제로 PROCESSING 전이에 성공한 row의 id만 claim 결과로
 * 반환한다. candidate 조회(SELECT)와 실제 반영(UPDATE)을 하나의 SQL 문(CTE)으로 묶었기 때문에,
 * "후보로 집었지만 실제로는 update되지 않은 row"가 claim 결과에 섞여 들어올 가능성이 구조적으로
 * 없다 — 두 단계로 나눠 candidateIds를 그대로 반환하던 이전 방식은 로직상 안전했지만(같은
 * 트랜잭션에서 row lock을 잡은 채 update했으므로), "조회 결과 = 실제 반영 결과"라는 보장이
 * 코드만 봐서는 명확하지 않았다. 이 방식은 그 보장을 SQL 자체가 대신 해준다.
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
        List<UUID> claimedIds = entityManager
                .createNativeQuery(
                        """
                        WITH candidates AS (
                            SELECT id FROM notification
                            WHERE status IN ('READY', 'RETRY_WAITING')
                              AND next_attempt_at <= :now
                            ORDER BY created_at
                            LIMIT :batchSize
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE notification n
                        SET status = 'PROCESSING',
                            worker_id = :workerId,
                            processing_started_at = :now,
                            lease_until = :leaseUntil,
                            updated_at = :now
                        FROM candidates c
                        WHERE n.id = c.id
                          AND n.status IN ('READY', 'RETRY_WAITING')
                        RETURNING n.id
                        """)
                .setParameter("now", now)
                .setParameter("batchSize", batchSize)
                .setParameter("workerId", workerId)
                .setParameter("leaseUntil", leaseUntil)
                .getResultList();

        return claimedIds;
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
