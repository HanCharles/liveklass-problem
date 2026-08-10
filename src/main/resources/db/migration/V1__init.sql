CREATE TABLE notification (
    id                     UUID PRIMARY KEY,
    recipient_id           VARCHAR(100)  NOT NULL,
    notification_type      VARCHAR(50)   NOT NULL,
    event_id                VARCHAR(100)  NOT NULL,
    reference_id            VARCHAR(100),
    channel                 VARCHAR(20)   NOT NULL,
    status                   VARCHAR(20)   NOT NULL,
    title                    VARCHAR(200)  NOT NULL,
    message                  VARCHAR(1000) NOT NULL,
    payload_json             JSONB,
    idempotency_key          VARCHAR(400)  NOT NULL,
    attempt_count            INT           NOT NULL DEFAULT 0,
    max_attempts              INT           NOT NULL DEFAULT 3,
    next_attempt_at           TIMESTAMPTZ,
    last_failure_reason       VARCHAR(1000),
    processing_started_at     TIMESTAMPTZ,
    lease_until                TIMESTAMPTZ,
    worker_id                 VARCHAR(100),
    sent_at                    TIMESTAMPTZ,
    read_at                    TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ   NOT NULL,
    updated_at                 TIMESTAMPTZ   NOT NULL
);

CREATE UNIQUE INDEX uk_notification_idempotency_key ON notification (idempotency_key);
CREATE INDEX idx_notification_status_next_attempt ON notification (status, next_attempt_at);
CREATE INDEX idx_notification_recipient_created ON notification (recipient_id, created_at DESC);
CREATE INDEX idx_notification_status_lease ON notification (status, lease_until);

CREATE TABLE notification_attempt (
    id               BIGSERIAL PRIMARY KEY,
    notification_id   UUID          NOT NULL REFERENCES notification (id),
    attempt_no         INT           NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    failure_reason      VARCHAR(1000),
    started_at           TIMESTAMPTZ   NOT NULL,
    finished_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_notification_attempt_notification_id ON notification_attempt (notification_id);
