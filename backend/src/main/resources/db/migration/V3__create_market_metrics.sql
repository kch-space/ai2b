-- Public market observations only; no API keys or account balances are stored here.
CREATE TABLE market_metric (
    id BIGINT NOT NULL AUTO_INCREMENT,
    instrument_id BIGINT NOT NULL,
    metric_type VARCHAR(32) NOT NULL,
    event_time DATETIME(3) NOT NULL,
    received_at DATETIME(3) NOT NULL,
    values_json TEXT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_metric_instrument FOREIGN KEY (instrument_id) REFERENCES instrument (id),
    CONSTRAINT uk_metric_identity UNIQUE (instrument_id, metric_type, event_time),
    CONSTRAINT ck_metric_type CHECK (metric_type IN ('OPEN_INTEREST','FUNDING_SNAPSHOT','FUNDING_HISTORY',
        'GLOBAL_ACCOUNT_RATIO','TOP_ACCOUNT_RATIO','TOP_POSITION_RATIO'))
);

CREATE TABLE market_metric_sync (
    instrument_id BIGINT NOT NULL,
    metric_type VARCHAR(32) NOT NULL,
    last_attempt_at DATETIME(3) NOT NULL,
    last_success_at DATETIME(3),
    next_allowed_at DATETIME(3) NOT NULL,
    failures INT NOT NULL,
    error_code VARCHAR(64),
    error_message VARCHAR(512),
    PRIMARY KEY (instrument_id, metric_type),
    CONSTRAINT fk_metric_sync_instrument FOREIGN KEY (instrument_id) REFERENCES instrument (id),
    CONSTRAINT ck_metric_failures CHECK (failures >= 0)
);
