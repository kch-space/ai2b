-- Only market identity and collector progress; no live data or rules are seeded.
-- All timestamps are UTC, with millisecond precision.
CREATE TABLE instrument (
    id BIGINT NOT NULL AUTO_INCREMENT,
    exchange VARCHAR(32) NOT NULL,
    market_type VARCHAR(32) NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    base_asset VARCHAR(32) NOT NULL,
    quote_asset VARCHAR(32) NOT NULL,
    margin_asset VARCHAR(32) NOT NULL,
    contract_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    price_tick_size DECIMAL(38, 18) NOT NULL,
    quantity_step_size DECIMAL(38, 18) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_instrument_identity UNIQUE (exchange, market_type, symbol),
    CONSTRAINT ck_instrument_price_tick CHECK (price_tick_size > 0),
    CONSTRAINT ck_instrument_quantity_step CHECK (quantity_step_size > 0)
);

CREATE INDEX idx_instrument_market_status ON instrument (exchange, market_type, status);

CREATE TABLE collector_checkpoint (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source VARCHAR(32) NOT NULL,
    stream_key VARCHAR(191) NOT NULL,
    last_event_time DATETIME(3),
    cursor_value TEXT,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_checkpoint_stream UNIQUE (source, stream_key)
);
