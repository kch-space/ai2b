-- Immutable engineering-example rules. Ratios, not display percentages; no trading advice.
CREATE TABLE alert_rule_version (
    id BIGINT NOT NULL PRIMARY KEY,
    rule_code VARCHAR(32) NOT NULL,
    version_number INT NOT NULL,
    direction VARCHAR(8) NOT NULL,
    window_minutes INT NOT NULL,
    trigger_ratio DECIMAL(20,18) NOT NULL,
    escalation_ratio DECIMAL(20,18) NOT NULL,
    recovery_ratio DECIMAL(20,18) NOT NULL,
    recovery_windows INT NOT NULL,
    cooldown_seconds INT NOT NULL,
    reminder_delta DECIMAL(20,18) NOT NULL,
    CONSTRAINT uk_rule_version UNIQUE (rule_code, version_number),
    CONSTRAINT ck_price_rule CHECK (direction IN ('UP','DOWN') AND window_minutes=5
        AND recovery_ratio>=0 AND trigger_ratio>recovery_ratio AND escalation_ratio>trigger_ratio
        AND recovery_windows>0 AND cooldown_seconds>0 AND reminder_delta>0)
);
INSERT INTO alert_rule_version VALUES (1,'PRICE_SURGE',1,'UP',5,0.05,0.10,0.02,2,900,0.01);
INSERT INTO alert_rule_version VALUES (2,'PRICE_DROP',1,'DOWN',5,0.05,0.10,0.02,2,900,0.01);

-- Only continuously collected new bars are eligible. Backfill and initial/restart catch-up never insert here.
CREATE TABLE market_kline_live (
    instrument_id BIGINT NOT NULL,
    open_time DATETIME(3) NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (instrument_id, open_time),
    CONSTRAINT fk_live_instrument FOREIGN KEY (instrument_id) REFERENCES instrument(id)
);
CREATE TABLE alert_rule_state (
    instrument_id BIGINT NOT NULL,
    rule_version_id BIGINT NOT NULL,
    last_window DATETIME(3),
    outcome VARCHAR(16) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    evaluated_at DATETIME(3) NOT NULL,
    recovery_count INT NOT NULL DEFAULT 0,
    change_ratio DECIMAL(38,18),
    PRIMARY KEY (instrument_id,rule_version_id),
    CONSTRAINT fk_state_instrument FOREIGN KEY (instrument_id) REFERENCES instrument(id),
    CONSTRAINT fk_state_version FOREIGN KEY (rule_version_id) REFERENCES alert_rule_version(id)
);
CREATE TABLE alert_event (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    instrument_id BIGINT NOT NULL,
    rule_version_id BIGINT NOT NULL,
    fingerprint VARCHAR(191) NOT NULL,
    active_fingerprint VARCHAR(191),
    status VARCHAR(16) NOT NULL,
    severity VARCHAR(2) NOT NULL,
    opened_at DATETIME(3) NOT NULL,
    last_seen_at DATETIME(3) NOT NULL,
    resolved_at DATETIME(3),
    hit_count INT NOT NULL,
    evidence_json TEXT NOT NULL,
    last_notified_at DATETIME(3) NOT NULL,
    last_notified_ratio DECIMAL(38,18) NOT NULL,
    CONSTRAINT uk_active_alert UNIQUE (active_fingerprint),
    CONSTRAINT fk_event_instrument FOREIGN KEY (instrument_id) REFERENCES instrument(id),
    CONSTRAINT fk_event_version FOREIGN KEY (rule_version_id) REFERENCES alert_rule_version(id),
    CONSTRAINT ck_event_state CHECK ((status='ACTIVE' AND active_fingerprint IS NOT NULL AND resolved_at IS NULL)
        OR (status='RESOLVED' AND active_fingerprint IS NULL AND resolved_at IS NOT NULL))
);
CREATE INDEX idx_event_recent ON alert_event(last_seen_at,id);
CREATE TABLE alert_event_history (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL,
    action VARCHAR(16) NOT NULL,
    severity VARCHAR(2) NOT NULL,
    data_time DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    evidence_json TEXT NOT NULL,
    CONSTRAINT fk_history_event FOREIGN KEY (event_id) REFERENCES alert_event(id)
);
CREATE INDEX idx_history_event ON alert_event_history(event_id,data_time);
CREATE TABLE notification_outbox (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL,
    history_id VARCHAR(36) NOT NULL,
    dedupe_key VARCHAR(191) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    recipient VARCHAR(32) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    lease_until DATETIME(3),
    lease_token VARCHAR(36),
    last_error VARCHAR(512),
    provider_message_id VARCHAR(80),
    created_at DATETIME(3) NOT NULL,
    sent_at DATETIME(3),
    CONSTRAINT uk_notification_dedupe UNIQUE(dedupe_key),
    CONSTRAINT fk_outbox_event FOREIGN KEY (event_id) REFERENCES alert_event(id),
    CONSTRAINT fk_outbox_history FOREIGN KEY (history_id) REFERENCES alert_event_history(id),
    CONSTRAINT ck_mock_only CHECK (channel='MOCK' AND recipient='LOCAL'),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','PROCESSING','SIMULATED','DEAD') AND attempts>=0)
);
CREATE INDEX idx_outbox_due ON notification_outbox(status,next_attempt_at,lease_until);
CREATE TABLE notification_attempt (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    outbox_id VARCHAR(36) NOT NULL,
    attempt_number INT NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    error_message VARCHAR(512),
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_attempt_outbox FOREIGN KEY(outbox_id) REFERENCES notification_outbox(id)
);
