package com.ai2b.crypto.kline;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.ai2b.crypto.instrument.Instrument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class KlineRepository {
    private final JdbcTemplate jdbc;
    public KlineRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public static String streamKey(String symbol) { return "USD_M_FUTURES:" + symbol + ":1m"; }

    public Optional<Instrument> findInstrument(String symbol) {
        return jdbc.query("""
                SELECT * FROM instrument WHERE exchange='BINANCE' AND market_type='USD_M_FUTURES'
                AND contract_type='PERPETUAL' AND quote_asset='USDT' AND margin_asset='USDT' AND symbol=?
                """, (rs, row) -> new Instrument(rs.getLong("id"), rs.getString("exchange"), rs.getString("market_type"),
                rs.getString("symbol"), rs.getString("base_asset"), rs.getString("quote_asset"), rs.getString("margin_asset"),
                rs.getString("contract_type"), rs.getString("status"), rs.getBigDecimal("price_tick_size"),
                rs.getBigDecimal("quantity_step_size"), rs.getTimestamp("updated_at").toInstant()), symbol).stream().findFirst();
    }

    public record Checkpoint(Instant start, Instant contiguousOpen, Instant lastWriteAt) { }

    public Optional<Checkpoint> checkpoint(String symbol) {
        return jdbc.query("""
                SELECT cursor_value,last_event_time,updated_at FROM collector_checkpoint
                WHERE source='BINANCE' AND stream_key=?
                """, (rs, row) -> new Checkpoint(Instant.ofEpochMilli(Long.parseLong(rs.getString(1))),
                rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()),
                streamKey(symbol)).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<Kline> list(long instrumentId, Instant start, Instant end) {
        return jdbc.query("""
                SELECT * FROM market_kline WHERE instrument_id=? AND interval_code='1m'
                AND open_time BETWEEN ? AND ? ORDER BY open_time
                """, (rs, row) -> new Kline(rs.getTimestamp("open_time").toInstant(), rs.getTimestamp("close_time").toInstant(),
                rs.getBigDecimal("open_price"), rs.getBigDecimal("high_price"), rs.getBigDecimal("low_price"),
                rs.getBigDecimal("close_price"), rs.getBigDecimal("base_volume"), rs.getBigDecimal("quote_volume"),
                rs.getLong("trade_count"), rs.getBigDecimal("taker_buy_base_volume"), rs.getBigDecimal("taker_buy_quote_volume"),
                rs.getTimestamp("received_at").toInstant()), instrumentId, Timestamp.from(start), Timestamp.from(end));
    }

    /** Candle rows and contiguous progress commit together; a sparse page never skips a gap. */
    @Transactional
    public Instant savePage(Instrument instrument, List<Kline> candles, Instant coverageStart,
            Instant requestedStart, Instant pageEnd, Instant writtenAt) {
        return save(instrument, candles, coverageStart, requestedStart, pageEnd, writtenAt, false);
    }

    @Transactional
    public Instant saveLivePage(Instrument instrument, List<Kline> candles, Instant coverageStart,
            Instant requestedStart, Instant pageEnd, Instant writtenAt) {
        return save(instrument, candles, coverageStart, requestedStart, pageEnd, writtenAt, true);
    }

    private Instant save(Instrument instrument, List<Kline> candles, Instant coverageStart,
            Instant requestedStart, Instant pageEnd, Instant writtenAt, boolean live) {
        if (candles.isEmpty()) throw new IllegalArgumentException("Empty page");
        jdbc.queryForObject("SELECT id FROM instrument WHERE id=? FOR UPDATE", Long.class, instrument.id());
        var existing = new java.util.HashSet<>(jdbc.query("""
                SELECT open_time FROM market_kline WHERE instrument_id=? AND interval_code='1m'
                AND open_time BETWEEN ? AND ?
                """, (rs, row) -> rs.getTimestamp(1).toInstant(), instrument.id(),
                Timestamp.from(requestedStart), Timestamp.from(pageEnd)));
        var newRows = candles.stream().filter(c -> !existing.contains(c.openTime())).toList();
        jdbc.batchUpdate("""
                INSERT INTO market_kline (instrument_id,interval_code,open_time,close_time,open_price,high_price,
                low_price,close_price,base_volume,quote_volume,trade_count,taker_buy_base_volume,taker_buy_quote_volume,received_at)
                VALUES (?,'1m',?,?,?,?,?,?,?,?,?,?,?,?)
                """, newRows, 499, (ps, candle) -> {
                    ps.setLong(1, instrument.id()); ps.setTimestamp(2, Timestamp.from(candle.openTime()));
                    ps.setTimestamp(3, Timestamp.from(candle.closeTime())); ps.setBigDecimal(4, candle.open());
                    ps.setBigDecimal(5, candle.high()); ps.setBigDecimal(6, candle.low()); ps.setBigDecimal(7, candle.close());
                    ps.setBigDecimal(8, candle.volume()); ps.setBigDecimal(9, candle.quoteVolume()); ps.setLong(10, candle.trades());
                    ps.setBigDecimal(11, candle.takerBuyVolume()); ps.setBigDecimal(12, candle.takerBuyQuoteVolume());
                    ps.setTimestamp(13, Timestamp.from(candle.receivedAt()));
                });
        if (live) {
            for (Kline candle : newRows) jdbc.update("""
                    INSERT INTO market_kline_live(instrument_id,open_time,observed_at) VALUES (?,?,?)
                    """, instrument.id(), Timestamp.from(candle.openTime()), Timestamp.from(writtenAt));
        }
        var allTimes = jdbc.query("""
                SELECT open_time FROM market_kline WHERE instrument_id=? AND interval_code='1m'
                AND open_time BETWEEN ? AND ? ORDER BY open_time
                """, (rs, row) -> rs.getTimestamp(1).toInstant(), instrument.id(), Timestamp.from(requestedStart), Timestamp.from(pageEnd));
        Instant next = requestedStart;
        for (Instant time : allTimes) {
            if (!time.equals(next)) break;
            next = next.plusMillis(Kline.MINUTE);
        }
        Instant contiguous = next.isAfter(coverageStart) ? next.minusMillis(Kline.MINUTE) : null;
        Timestamp watermark = contiguous == null ? null : Timestamp.from(contiguous);
        Timestamp now = Timestamp.from(writtenAt);
        int updated = jdbc.update("""
                UPDATE collector_checkpoint SET last_event_time=?,cursor_value=?,updated_at=?
                WHERE source='BINANCE' AND stream_key=?
                """, watermark, Long.toString(coverageStart.toEpochMilli()), now, streamKey(instrument.symbol()));
        if (updated == 0) jdbc.update("""
                INSERT INTO collector_checkpoint (source,stream_key,last_event_time,cursor_value,created_at,updated_at)
                VALUES ('BINANCE',?,?,?,?,?)
                """, streamKey(instrument.symbol()), watermark, Long.toString(coverageStart.toEpochMilli()), now, now);
        return contiguous;
    }
}
