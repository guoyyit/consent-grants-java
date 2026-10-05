-- Versioned scope definitions
CREATE TABLE IF NOT EXISTS scope_version (
    version     VARCHAR(64)  PRIMARY KEY,
    created_at  BIGINT       NOT NULL,
    active      BOOLEAN      NOT NULL
);

CREATE TABLE IF NOT EXISTS scope_item (
    version     VARCHAR(64) NOT NULL,
    item_key    VARCHAR(128) NOT NULL,
    status      VARCHAR(16) NOT NULL,   -- ACTIVE | DEPRECATED | RETIRED
    replaces    VARCHAR(128),           -- key carried over / renamed from previous version
    split_into  VARCHAR(512),           -- comma separated successor keys (for splits)
    display_name VARCHAR(256),
    CONSTRAINT pk_scope_item PRIMARY KEY (version, item_key)
);

-- One uniquely-converged grant per (user, client), pinned to one scope version
CREATE TABLE IF NOT EXISTS consent_grant (
    user_id       VARCHAR(128) NOT NULL,
    client_id     VARCHAR(128) NOT NULL,
    scope_version VARCHAR(64)  NOT NULL,
    state         VARCHAR(16)  NOT NULL,  -- ACTIVE | REVOKED
    version_seq   BIGINT       NOT NULL,  -- increments only when content actually changes
    created_at    BIGINT       NOT NULL,
    updated_at    BIGINT       NOT NULL,
    CONSTRAINT pk_consent_grant PRIMARY KEY (user_id, client_id)
);

-- Per-item decisions converge by primary key: repeated submissions never duplicate rows
CREATE TABLE IF NOT EXISTS consent_item_decision (
    user_id    VARCHAR(128) NOT NULL,
    client_id  VARCHAR(128) NOT NULL,
    item_key   VARCHAR(128) NOT NULL,
    decision   VARCHAR(16)  NOT NULL,     -- GRANTED | DENIED
    decided_at BIGINT       NOT NULL,
    CONSTRAINT pk_decision PRIMARY KEY (user_id, client_id, item_key)
);

-- Issued constrained credentials
CREATE TABLE IF NOT EXISTS access_token (
    token_value     VARCHAR(128) PRIMARY KEY,
    user_id         VARCHAR(128) NOT NULL,
    client_id       VARCHAR(128) NOT NULL,
    scope_version   VARCHAR(64)  NOT NULL,
    scope_keys      VARCHAR(2048) NOT NULL,   -- granted item keys at issue time
    issued_at       BIGINT NOT NULL,
    expires_at      BIGINT NOT NULL,
    state           VARCHAR(16) NOT NULL,     -- ACTIVE | REVOKED | EXPIRED
    propagation     VARCHAR(16) NOT NULL,     -- NOT_REQUIRED | PENDING | DONE | FAILED
    attempts        INT NOT NULL DEFAULT 0,
    last_error      VARCHAR(1024),
    revocation_id   BIGINT
);
CREATE INDEX IF NOT EXISTS idx_token_revoke ON access_token(revocation_id);
CREATE INDEX IF NOT EXISTS idx_token_propagation ON access_token(propagation);

-- Recoverable revocation propagation units of work
CREATE TABLE IF NOT EXISTS revocation_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     VARCHAR(128) NOT NULL,
    client_id   VARCHAR(128) NOT NULL,
    status      VARCHAR(16) NOT NULL,        -- PENDING | PROPAGATED
    attempts    INT NOT NULL DEFAULT 0,
    last_error  VARCHAR(1024),
    created_at  BIGINT NOT NULL,
    updated_at  BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_revocation_status ON revocation_event(status);

-- Access audit trail (including denied / unauthorized attempts)
CREATE TABLE IF NOT EXISTS audit_event (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_time     BIGINT NOT NULL,
    actor          VARCHAR(256) NOT NULL,
    subject_user   VARCHAR(128),
    client_id      VARCHAR(128),
    action         VARCHAR(64) NOT NULL,
    result         VARCHAR(16) NOT NULL,     -- ALLOWED | DENIED
    detail         VARCHAR(1024)
);
CREATE INDEX IF NOT EXISTS idx_audit_subject ON audit_event(subject_user);
CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_event(event_time);

-- Idempotency: replay of the same submission key converges to the same stored outcome
CREATE TABLE IF NOT EXISTS idempotency_record (
    idem_key       VARCHAR(128) PRIMARY KEY,
    user_id        VARCHAR(128) NOT NULL,
    client_id      VARCHAR(128) NOT NULL,
    request_hash   VARCHAR(128) NOT NULL,
    outcome        VARCHAR(16)  NOT NULL,   -- CONSENT_SUBMIT | TOKEN_ISSUE
    created_at     BIGINT       NOT NULL
);
