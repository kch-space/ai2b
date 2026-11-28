CREATE TABLE integration_config (
    kind VARCHAR(16) NOT NULL PRIMARY KEY,
    version_number BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    settings_json TEXT NOT NULL,
    secret_ciphertext TEXT,
    updated_at DATETIME(3) NOT NULL
);
INSERT INTO integration_config VALUES ('TELEGRAM',0,FALSE,'{"chatId":"","minimumSeverity":"P1","includeEvidence":true}',NULL,CURRENT_TIMESTAMP);
INSERT INTO integration_config VALUES ('AI',0,FALSE,'{"baseUrl":"https://api.openai.com/v1","model":"","temperature":"0.2","maxTokens":"1024"}',NULL,CURRENT_TIMESTAMP);
CREATE TABLE integration_audit (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    kind VARCHAR(16) NOT NULL,
    action VARCHAR(32) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    detail VARCHAR(256) NOT NULL,
    created_at DATETIME(3) NOT NULL
);
CREATE INDEX idx_integration_audit ON integration_audit(created_at);
CREATE TABLE integration_ai_report (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL,
    config_version BIGINT NOT NULL,
    model VARCHAR(128) NOT NULL,
    evidence_json TEXT NOT NULL,
    report_text TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL
);
ALTER TABLE notification_outbox DROP CONSTRAINT ck_mock_only;
ALTER TABLE notification_outbox DROP CONSTRAINT ck_outbox_status;
ALTER TABLE notification_outbox ADD config_version BIGINT;
ALTER TABLE notification_outbox ADD CONSTRAINT ck_outbox_channel CHECK (channel IN ('MOCK','TELEGRAM'));
ALTER TABLE notification_outbox ADD CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','PROCESSING','SIMULATED','SENT','CANCELLED','DEAD') AND attempts>=0);
