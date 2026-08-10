# 알림 발송 시스템 (과제 C)

수강 신청 완료, 결제 확정, 강의 시작 D-1, 취소 처리 등 이벤트 발생 시 사용자에게 EMAIL 또는
IN_APP 알림을 발송하는 시스템이다. 실제 발송은 API 요청 스레드와 분리된 비동기 worker가
처리하며, 실패 시 재시도하고, 동일 이벤트에 대한 중복 발송을 방지하며, 서버 재시작 및 worker
장애 상황에서도 알림이 유실되지 않도록 설계했다.

## 목차

- [프로젝트 개요](#프로젝트-개요)
- [기술 스택](#기술-스택)
- [실행 방법](#실행-방법)
- [API 목록 및 예시](#api-목록-및-예시)
- [데이터 모델 설명](#데이터-모델-설명)
- [요구사항 해석 및 가정](#요구사항-해석-및-가정)
- [설계 결정과 이유](#설계-결정과-이유)
- [비동기 처리 구조](#비동기-처리-구조)
- [상태 전이표](#상태-전이표)
- [재시도 정책](#재시도-정책)
- [중복 발송 방지 전략](#중복-발송-방지-전략)
- [서버 재시작 후 복구 전략](#서버-재시작-후-복구-전략)
- [다중 인스턴스 환경 고려](#다중-인스턴스-환경-고려)
- [테스트 실행 방법](#테스트-실행-방법)
- [미구현 / 제약사항](#미구현--제약사항)
- [AI 활용 범위](#ai-활용-범위)
- [검증 결과](#검증-결과)

## 프로젝트 개요

- 과제 C(알림 발송 시스템)를 Spring Boot 단일 애플리케이션으로 구현했다.
- 알림 발송 요청 등록 API는 실제 발송이 아니라 "비동기 처리 대상 등록"을 의미하며, 응답은
  요청 접수 완료를 뜻한다.
- 실제 발송은 `@Scheduled` 기반 worker가 DB에서 발송 가능한 알림을 claim하여 처리한다
  (DB 기반 Outbox/Queue 패턴, 실제 메시지 브로커는 사용하지 않음).
- 발송 실패는 원 비즈니스 트랜잭션에 영향을 주지 않되, 무시하지 않고 실패 사유·재시도
  횟수·다음 재시도 시각·최종 실패 상태를 DB에 기록한다.
- 동일 이벤트에 대한 중복 발송은 DB unique constraint + 트랜잭션 처리로 방지한다.
- 서버 재시작, worker 장애(stuck PROCESSING), 다중 인스턴스 환경에서의 중복 처리 방지를
  고려했다.

## 기술 스택

| 영역 | 선택 |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.3.x |
| Build | Gradle (wrapper 포함) |
| Persistence | Spring Data JPA + PostgreSQL native query 일부 |
| DB | PostgreSQL 16 |
| Migration | Flyway |
| Container | Docker Compose |
| Test | JUnit5, AssertJ, Testcontainers(PostgreSQL), Awaitility |

## 실행 방법

### 사전 요구사항

- JDK 17
- Docker / Docker Desktop (DB 및 테스트용 Testcontainers 실행에 필요)

### 로컬 실행

```bash
# 1. PostgreSQL 기동
docker compose up -d

# 2. 애플리케이션 실행
./gradlew bootRun
```

애플리케이션은 기본적으로 `http://localhost:8080` 에서 기동된다.

접속 정보(기본값, 필요 시 환경변수로 재정의 가능):

| 환경변수 | 기본값 |
|---|---|
| `DB_HOST` | localhost |
| `DB_PORT` | 5432 |
| `DB_NAME` | notification |
| `DB_USER` | notification |
| `DB_PASSWORD` | notification |
| `SERVER_PORT` | 8080 |

### 종료

```bash
docker compose down          # 데이터 유지
docker compose down -v       # 데이터까지 삭제
```

## API 목록 및 예시

공통 응답 포맷:

```json
{
  "success": true,
  "data": { ... },
  "errorCode": null,
  "message": null
}
```

실패 시:

```json
{
  "success": false,
  "data": null,
  "errorCode": "NOTIFICATION_NOT_FOUND",
  "message": "알림을 찾을 수 없습니다."
}
```

### 1. 알림 발송 요청 등록

```
POST /api/notifications
```

요청:

```bash
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "recipientId": "user-1",
    "notificationType": "ENROLLMENT_COMPLETED",
    "eventId": "event-1001",
    "referenceId": "course-1",
    "channel": "EMAIL",
    "payload": { "courseTitle": "Spring Boot 입문" }
  }'
```

응답 (201 Created, 요청 접수 완료 — 발송 성공을 의미하지 않음):

```json
{
  "success": true,
  "data": {
    "notificationId": "b6f1e6b0-2f7a-4b8e-8c3f-2a6f3e2b9c11",
    "recipientId": "user-1",
    "notificationType": "ENROLLMENT_COMPLETED",
    "eventId": "event-1001",
    "referenceId": "course-1",
    "channel": "EMAIL",
    "status": "READY",
    "title": "수강 신청이 완료되었습니다.",
    "message": "Spring Boot 입문 수강 신청이 완료되었습니다.",
    "attemptCount": 0,
    "maxAttempts": 3,
    "nextAttemptAt": "2026-08-11T04:30:00Z",
    "lastFailureReason": null,
    "sentAt": null,
    "readAt": null,
    "createdAt": "2026-08-11T04:30:00Z",
    "updatedAt": "2026-08-11T04:30:00Z"
  }
}
```

동일한 `recipientId + notificationType + eventId + channel` 조합으로 다시 요청하면 새 row를
만들지 않고 기존 알림 정보를 그대로 반환한다(같은 응답, 같은 `notificationId`).

### 2. 알림 상태 조회

```
GET /api/notifications/{notificationId}
```

```bash
curl http://localhost:8080/api/notifications/b6f1e6b0-2f7a-4b8e-8c3f-2a6f3e2b9c11
```

### 3. 사용자 알림 목록 조회

```
GET /api/users/{recipientId}/notifications?read=false&channel=EMAIL&status=SENT&page=0&size=20
```

```bash
curl "http://localhost:8080/api/users/user-1/notifications?read=false&page=0&size=20"
```

응답:

```json
{
  "success": true,
  "data": {
    "items": [ { "notificationId": "...", "status": "SENT", "...": "..." } ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

필터는 모두 선택값이며 생략 가능하다(`read`, `channel`, `status`).

### 4. 읽음 처리 (멱등)

```
PATCH /api/notifications/{notificationId}/read
```

```bash
curl -X PATCH http://localhost:8080/api/notifications/b6f1e6b0-2f7a-4b8e-8c3f-2a6f3e2b9c11/read
```

이미 읽은 알림에 다시 호출해도 200 OK를 반환하며, `readAt`은 최초 값을 유지한다(갱신하지 않음).

### 5. 수동 재시도 (선택 구현)

```
POST /api/notifications/{notificationId}/retry
```

```bash
curl -X POST http://localhost:8080/api/notifications/b6f1e6b0-2f7a-4b8e-8c3f-2a6f3e2b9c11/retry
```

FAILED 상태의 알림만 재시도 가능하며, 성공 시 `attemptCount=0`, `status=READY`,
`nextAttemptAt=now`로 초기화된다. FAILED가 아닌 알림에 호출하면 `409 Conflict`
(`INVALID_RETRY_STATE`)를 반환한다.

## 데이터 모델 설명

### notification

| 컬럼 | 설명 |
|---|---|
| id | UUID PK. 외부에 노출되는 식별자이므로 예측 불가능한 UUID를 사용 |
| recipient_id | 수신자 ID |
| notification_type | 알림 타입(enum) |
| event_id | 참조 이벤트 ID |
| reference_id | 참조 데이터(강의 ID 등) |
| channel | EMAIL / IN_APP |
| status | READY / PROCESSING / SENT / RETRY_WAITING / FAILED |
| title / message | 등록 시점에 템플릿으로 렌더링해 저장 |
| payload_json | 원본 payload(JSONB) |
| idempotency_key | `recipientId\|notificationType\|eventId\|channel`, UNIQUE |
| attempt_count | 누적 실패 횟수(성공 시 증가하지 않음) |
| max_attempts | 최대 시도 횟수 |
| next_attempt_at | 다음 재시도(또는 최초 처리) 가능 시각 |
| last_failure_reason | 가장 최근 실패 사유 |
| processing_started_at / lease_until / worker_id | worker claim 및 lease 관리용 |
| sent_at / read_at | 발송/읽음 시각 |
| created_at / updated_at | 감사 컬럼 |

### notification_attempt

발송 시도별 이력. `notification.last_failure_reason`은 최신값만 담고, 이 테이블은 매 시도의
`attempt_no`, `status`(SUCCESS/FAILURE), `failure_reason`, `started_at`, `finished_at`을
전부 남긴다.

### unique constraint / idempotency key

```
idempotency_key = recipientId + "|" + notificationType + "|" + eventId + "|" + channel
```

이 조합에 `UNIQUE INDEX`를 걸었다. 같은 `eventId`라도 recipient·타입·채널이 다르면 서로 다른
알림으로 취급해야 하므로, `eventId` 단독이 아니라 4개 필드 조합을 키로 사용했다.

## 요구사항 해석 및 가정

- 알림 요청 API는 실제 발송 성공을 보장하는 API가 아니라, **발송 요청을 안전하게 접수하는 API**로
  해석했다. 응답의 `status=READY`는 "접수됨"을 의미하며 "발송됨"을 의미하지 않는다.
- 알림 발송 실패는 원 비즈니스 트랜잭션 실패로 전파하지 않는다. 다만 실패를 무시하지 않고
  실패 사유, 재시도 횟수, 다음 재시도 시각, 최종 실패 상태를 DB에 기록한다.
- 메시지 브로커는 과제 제약상 사용하지 않았다. 대신 DB 기반 Outbox/Queue 구조로 구현하여
  추후 Kafka, SQS, RabbitMQ 등으로 교체 가능한 구조를 의도했다(아래 [비동기 처리 구조](#비동기-처리-구조) 참고).
- 동일 이벤트 중복 발송 방지는 애플리케이션 로직만으로 처리하지 않고, DB unique constraint와
  트랜잭션 처리를 함께 사용했다.
- 다중 인스턴스 worker 환경에서는 row lock(`FOR UPDATE SKIP LOCKED`) + 상태 조건부 update
  방식을 통해 동일 알림이 동시에 처리되지 않도록 설계했다.
- 요청 접수와 발송 대기 사이에 별도의 검증/승인 단계가 없다고 판단해 `REQUESTED` 같은 중간
  상태를 두지 않고, 알림 요청 생성 직후 바로 `READY` 상태로 저장한다.

## 설계 결정과 이유

### Java 17 선택 이유

Kotlin도 선택 가능했지만, Spring 백엔드 과제를 안정적으로 완성하고 설명하는 것이 이번
과제의 핵심 목표(멱등성, 동시성 제어, 상태 전이, 테스트, 문서화)라고 판단했다. Kotlin은
Android 개발 경험은 있으나 Spring 서버 개발 경험은 Java가 더 많아, 새로운 언어를 어필하기보다
Java 17 기반으로 도메인 상태 전이·트랜잭션·테스트·README 완성도를 높이는 방향을 선택했다.

### JPA 선택 이유

알림이라는 도메인 객체의 상태 전이(READY→PROCESSING→SENT 등)를 객체 중심으로 표현하기
좋고, 일반적인 등록/조회/상태 변경 API를 구현하기에 적합해 JPA를 선택했다.

다만 worker가 처리할 알림을 claim하는 부분은 DB row lock과 조건부 상태 변경이 핵심이므로,
이 부분(`NotificationClaimRepository`)만은 JPA 추상화 대신 PostgreSQL native query
(`FOR UPDATE SKIP LOCKED` + 조건부 `UPDATE`)를 명시적으로 사용했다. ORM만 고집하기보다
요구사항(동시성 제어)에 맞게 SQL을 명시적으로 사용하는 것이 더 낫다고 판단했다.

### PostgreSQL 선택 이유

H2는 실행 편의성은 좋지만 실제 운영 DB와 트랜잭션/락 동작이 다를 수 있다. 이번 과제는 단순
CRUD가 아니라 동시 등록 시 멱등성 보장, worker의 안전한 claim, 처리 중 서버 종료 시 stuck
복구, 다중 인스턴스 중복 처리 방지가 핵심이므로, `FOR UPDATE SKIP LOCKED`,
`UPDATE ... WHERE`, unique constraint, row-level lock, JSONB를 명확히 보여줄 수 있는
PostgreSQL을 선택했다.

MySQL도 InnoDB의 row-level lock과 unique constraint로 충분히 구현 가능하고 실제로 MySQL
운영 경험이 더 많지만, "내가 익숙해서"가 아니라 "이번 과제의 동시성 요구사항을 표현하기에
가장 적합해서" PostgreSQL을 선택했다.

### 메시지 브로커를 쓰지 않는 이유

과제 원문에서 실제 메시지 브로커 설치는 불필요하다고 명시되어 있어, DB 기반 Notification
Outbox/Queue 구조로 구현했다. 운영 환경에서는 이 구조를 Kafka/SQS/RabbitMQ 등으로
확장할 수 있다(아래 참고).

## 비동기 처리 구조

```
[NotificationController] --(등록)--> [notification 테이블, status=READY]
                                              |
                                   (Scheduled polling, 별도 스레드)
                                              v
                              [NotificationClaimRepository.claim()]
                        SELECT ... FOR UPDATE SKIP LOCKED  (짧은 트랜잭션 A)
                        UPDATE ... SET status='PROCESSING' (같은 트랜잭션 A, 즉시 커밋)
                                              |
                                   (트랜잭션 없이 실제 발송 호출)
                                     [NotificationSender.send()]
                                              |
                                 (결과만 별도 트랜잭션 B로 반영)
                        [NotificationRetryService.recordSuccess/Failure()]
                              -> SENT / RETRY_WAITING / FAILED
```

- API 요청 스레드는 알림을 `READY` 상태로 저장하고 즉시 응답한다(등록과 발송이 완전히 분리됨).
- `NotificationProcessor`가 claim → sender 호출 → 결과 반영의 3단계를 조율한다.
- **claim 트랜잭션과 sender 호출을 분리**했다: claim은 `NotificationClaimRepository`에서
  `REQUIRES_NEW`로 즉시 커밋되는 짧은 트랜잭션으로 끝내고, 실제 외부 발송 호출(`sender.send()`)은
  트랜잭션 밖에서 수행한다. 이렇게 하지 않으면 외부 발송이 느려지거나 멈췄을 때 DB 커넥션과
  row lock을 오래 붙잡아 다른 worker/요청을 막게 된다.
- 발송 결과(성공/실패)는 `NotificationRetryService`가 알림 건별로 별도의 짧은 트랜잭션에서
  반영한다.
- 장점: 구현이 단순하고 별도 인프라가 필요 없으며, DB 트랜잭션 안에서 정합성을 보장하기
  쉽다. 단점: polling 주기만큼 지연이 발생하고, 처리량이 늘어나면 DB에 부하가 집중된다.
- **운영 환경 확장 방향**: `NotificationSender` 인터페이스와 claim/상태 전이 로직은 그대로
  두고, `@Scheduled` polling을 Kafka/SQS 컨슈머 또는 RabbitMQ 리스너로 교체하면 된다.
  `notification` 테이블 자체가 이미 outbox 역할을 하므로, "DB에 쓰고 별도 프로세스가
  읽어서 처리한다"는 구조는 그대로 유지된다.

## 상태 전이표

| From | To | 조건 |
|---|---|---|
| READY | PROCESSING | worker가 claim(`FOR UPDATE SKIP LOCKED` + 조건부 UPDATE) |
| PROCESSING | SENT | sender 발송 성공 |
| PROCESSING | RETRY_WAITING | 발송 실패, `attemptCount < maxAttempts` |
| RETRY_WAITING | PROCESSING | `nextAttemptAt` 경과 후 worker가 다시 claim |
| PROCESSING | FAILED | 발송 실패, `attemptCount >= maxAttempts` |
| PROCESSING | READY | lease timeout(stuck) 복구, attemptCount 증가 없음 |
| FAILED | READY | 운영자 수동 재시도(`POST /retry`), attemptCount=0으로 초기화 |

## 재시도 정책

- 최대 재시도 횟수: 3회(`notification.retry.max-attempts`)
- Exponential backoff: 운영 기본값 1분 / 5분 / 15분(`notification.retry.backoff-seconds`)
- 테스트 프로파일에서는 1초 / 2초 / 3초로 오버라이드해 빠르게 검증한다.
- 실패할 때마다 `attemptCount`를 증가시키고 `lastFailureReason`을 갱신한다.
- 매 시도는 `notification_attempt`에 SUCCESS/FAILURE로 이력이 남는다.
- `attemptCount >= maxAttempts`가 되면 `FAILED`로 전이하고 더 이상 자동 재시도하지 않는다
  (선택 구현인 수동 재시도로만 복구 가능).

## 중복 발송 방지 전략

1. `idempotency_key`(recipientId+notificationType+eventId+channel)에 UNIQUE INDEX.
2. 등록 시 먼저 `idempotency_key`로 조회 → 있으면 기존 row 반환, 없으면 새로 insert.
3. **동시 요청 레이스 대응**: 두 요청이 동시에 "없음"을 보고 각자 insert를 시도해도, DB unique
   constraint가 최종 방어선이 되어 하나만 성공한다. 나머지는
   `DataIntegrityViolationException`을 잡아 재조회 후 기존 row를 반환한다(row는 항상 1개).
   이 시나리오는 `NotificationCommandConcurrencyTest`에서 20개 스레드로 동시 등록해 검증한다.
4. 서로 다른 recipient/notificationType/channel은 같은 eventId라도 별도 알림으로 취급한다
   (unique key에 4개 필드가 모두 포함되므로).

## 서버 재시작 후 복구 전략

- 알림 요청과 상태는 모두 DB에 저장되므로, 서버가 재시작되어도 다음 상태의 알림은 그대로
  다시 처리 대상이 된다: `READY`, `nextAttemptAt`이 지난 `RETRY_WAITING`.
- worker가 알림을 `PROCESSING`으로 바꾼 뒤 서버가 죽으면 해당 알림이 영원히 처리되지 않을
  수 있다. 이를 막기 위해 `processingStartedAt`, `leaseUntil`, `workerId`를 두고,
  `StuckNotificationRecoveryWorker`가 주기적으로 `status=PROCESSING AND lease_until < now()`인
  알림을 `READY`로 되돌린다.
- 단순 lease 만료 자체는 발송 실패로 보지 않으므로 `attemptCount`는 증가시키지 않는다(다만
  `lastFailureReason`에 `"processing lease expired"`를 남긴다). 실제 sender 호출 실패가
  확인된 경우에만 `attemptCount`가 증가한다.

## 다중 인스턴스 환경 고려

여러 worker(애플리케이션 인스턴스)가 동시에 polling해도 같은 알림이 중복 처리되지 않도록
`NotificationClaimRepository.claim()`에서 다음을 사용한다.

1. `SELECT id ... FOR UPDATE SKIP LOCKED LIMIT :batchSize` — 이미 다른 인스턴스가 잠근
   row는 건너뛰고, 잠기지 않은 row만 골라 즉시 잠근다.
2. `UPDATE notification SET status='PROCESSING', ... WHERE id IN (:ids) AND status IN ('READY','RETRY_WAITING')`
   — 방어적으로 상태를 한 번 더 확인한 뒤 전이시킨다.
3. 이 두 쿼리는 하나의 짧은 트랜잭션(`REQUIRES_NEW`)으로 즉시 커밋되어, 다른 인스턴스가
   다음 polling에서 곧바로 최신 상태를 보게 된다.

이 방식은 애플리케이션 레벨의 분산 락(Redis 등) 없이도 PostgreSQL의 row-level lock만으로
다중 인스턴스 중복 처리를 방지한다.

## 테스트 실행 방법

```bash
./gradlew test
```

Docker(Testcontainers)가 필요하다 — 테스트 실행 시 임시 PostgreSQL 컨테이너가 자동으로
기동된다(별도로 `docker compose up`을 할 필요 없음).

### 주요 테스트 케이스

| 파일 | 검증 내용 |
|---|---|
| `domain/RetryPolicyTest`, `domain/NotificationTest` | 재시도 backoff 계산, 상태 전이 도메인 로직(단위 테스트) |
| `application/NotificationCommandServiceTest` | 등록 성공, 동일 idempotency key 재요청 시 기존 row 반환, 채널/수신자가 다르면 별도 알림 |
| `application/NotificationCommandConcurrencyTest` | 20개 스레드 동시 등록 시 row 1개만 생성(동시성) |
| `application/NotificationProcessorTest` | READY→SENT, 실패 시 RETRY_WAITING(+attemptCount 증가), maxAttempts 초과 시 FAILED, nextAttemptAt 지난 것만 재처리, EMAIL/IN_APP 채널 분기 |
| `infrastructure/worker/StuckNotificationRecoveryWorkerTest` | lease 만료된 PROCESSING → READY 복구(attemptCount 미증가), lease 유효한 건은 그대로 유지 |
| `application/NotificationQueryServiceTest` | 목록 조회 필터(읽음/채널), 읽음 처리 반복 호출 안전성 |
| `application/ManualRetryServiceTest` | FAILED → READY 수동 재시도, FAILED가 아닌 건 재시도 거부 |
| `api/NotificationControllerApiTest` | HTTP 계층 end-to-end(등록→조회→목록→읽음→재시도 거부), 검증 실패 400, 미존재 404 |
| `infrastructure/worker/SchedulerWiringTest` | `@Scheduled` 배선 자체가 실제로 동작하는지(유일하게 실시간 polling에 의존) |

대부분의 테스트는 `@Scheduled` 타이밍에 의존하지 않고 `NotificationProcessor.processOnce()` /
`StuckNotificationRecoveryWorker.recoverOnce()`를 직접 호출해 결정적으로 검증한다.

## 미구현 / 제약사항

- 실제 이메일 발송은 하지 않는다. `MockEmailSender`가 로그로 대체하며, 테스트에서
  eventId 기준으로 "N번 실패 후 성공" / "항상 실패"를 설정할 수 있다.
- 실제 메시지 브로커(Kafka/RabbitMQ/SQS)는 사용하지 않았다. 대신 DB 기반 Outbox/Queue로
  구현했으며, 대규모 트래픽 환경에서는 DB polling queue보다 메시지 브로커가 더 적합할 수 있다.
- **발송 스케줄링(특정 시각 예약 발송)은 구현하지 않았다.** 시간 관계상 우선순위 4번(선택
  구현 중 가장 낮은 우선순위)이었던 예약 발송은 제외했다. 확장한다면 `notification`에
  `scheduled_at` 컬럼을 추가하고 claim 쿼리의 조건에 `scheduled_at <= now()`를 포함시키는
  방식으로 기존 구조를 그대로 재사용할 수 있다.
- 인증/인가는 과제 허용 범위에 따라 생략했다(`recipientId`를 요청 바디/경로로 직접 전달).
- 알림 템플릿은 enum 기반 provider(`NotificationTemplateProvider`)로 단순하게 구현했다.
  실제 운영에서는 DB 기반 템플릿 관리 또는 외부 템플릿 엔진으로 교체할 수 있다.

## AI 활용 범위

- AI 코딩 에이전트(Claude Code)를 사용해 초기 프로젝트 구조 설계와 반복적인 보일러플레이트
  코드(엔티티, 리포지토리, DTO, 설정 파일) 생성을 보조받았다.
- 최종 설계 판단(기술 스택 선택, 상태 전이 모델, 멱등성 기준, claim 트랜잭션 분리 전략,
  재시도 정책, stuck 복구 정책, 테스트 케이스 구성)은 직접 검토하고 결정했다.
- 생성된 코드는 `./gradlew test`로 직접 실행하고, 아래 [검증 결과](#검증-결과) 섹션의
  시나리오를 통해 검증했다.
- 그대로 복사한 산출물이 아니라, 요구사항(특히 동시성/장애 복구 시나리오)에 맞게 구조를
  조정하고 검증한 결과물이다.

## 검증 결과

### 테스트 실행

```bash
./gradlew test
```

`./gradlew test` 실행 결과 **테스트 클래스 10개, 총 30개 테스트 전부 통과**했다(단위 테스트 9개
+ Testcontainers 기반 통합/동시성/HTTP/스케줄러 테스트 21개). 상세 리포트는
`build/reports/tests/test/index.html`에서 확인할 수 있다.

### 직접 확인한 시나리오

1. 동일한 알림 요청을 여러 번(순차 및 동시) 호출해도 `notification` row는 1개만 생성됨.
2. `READY` 상태 알림은 worker에 의해 `PROCESSING`을 거쳐 `SENT`로 전이됨.
3. `MockEmailSender`를 실패하도록 설정하면 `RETRY_WAITING` 상태로 전이되고 `attemptCount`가
   증가하며, `notification_attempt`에 실패 이력이 남음.
4. 최대 재시도 횟수(3회)를 초과하면 `FAILED` 상태로 전이되고 더 이상 자동 재처리되지 않음.
5. `PROCESSING` 상태에서 `leaseUntil`이 지난 알림은 `StuckNotificationRecoveryWorker`에
   의해 다시 `READY`로 복구되며, 이때 `attemptCount`는 증가하지 않음.
6. 읽음 처리 API(`PATCH /read`)는 여러 번 호출해도 `readAt`이 최초 값으로 유지되고 매번
   200 OK를 반환함(오류 없음).
7. `FAILED` 알림에 수동 재시도 API를 호출하면 `attemptCount=0`, `status=READY`로 초기화되고,
   `FAILED`가 아닌 알림에 호출하면 409 Conflict가 반환됨.
8. EMAIL/IN_APP 채널에 따라 서로 다른 `NotificationSender` 구현체가 호출됨.

<!-- 아래는 채용 지원자가 실제 로컬 실행 후 직접 채워 넣을 수 있는 자리 -->
### 로컬 실행 확인 (선택)

- [ ] `docker compose up -d` 후 `./gradlew bootRun` 정상 기동 확인
- [ ] curl로 등록 → 상태 조회 → 목록 조회 → 읽음 처리 → (강제 실패 후) 재시도 흐름 확인
- [ ] `docker exec -it notification-postgres psql -U notification -d notification` 로
      `notification`, `notification_attempt` 테이블 상태 전이 직접 확인
