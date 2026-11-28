-- Closed 1m candles only. Times are UTC; API decimals remain strings.
CREATE TABLE market_kline (
    id BIGINT NOT NULL AUTO_INCREMENT,
    instrument_id BIGINT NOT NULL,
    interval_code VARCHAR(8) NOT NULL,
    open_time DATETIME(3) NOT NULL,
    close_time DATETIME(3) NOT NULL,
    open_price DECIMAL(38, 18) NOT NULL,
    high_price DECIMAL(38, 18) NOT NULL,
    low_price DECIMAL(38, 18) NOT NULL,
    close_price DECIMAL(38, 18) NOT NULL,
    base_volume DECIMAL(38, 18) NOT NULL,
    quote_volume DECIMAL(38, 18) NOT NULL,
    trade_count BIGINT NOT NULL,
    taker_buy_base_volume DECIMAL(38, 18) NOT NULL,
    taker_buy_quote_volume DECIMAL(38, 18) NOT NULL,
    received_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_kline_instrument FOREIGN KEY (instrument_id) REFERENCES instrument (id),
    CONSTRAINT uk_kline_identity UNIQUE (instrument_id, interval_code, open_time),
    CONSTRAINT ck_kline_interval CHECK (interval_code = '1m'),
    CONSTRAINT ck_kline_prices CHECK (low_price > 0 AND high_price >= low_price
        AND open_price BETWEEN low_price AND high_price AND close_price BETWEEN low_price AND high_price),
    CONSTRAINT ck_kline_volumes CHECK (base_volume >= 0 AND quote_volume >= 0 AND trade_count >= 0
        AND taker_buy_base_volume >= 0 AND taker_buy_base_volume <= base_volume
        AND taker_buy_quote_volume >= 0 AND taker_buy_quote_volume <= quote_volume),
    CONSTRAINT ck_kline_times CHECK (close_time > open_time AND received_at > close_time)
);
