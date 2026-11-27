package com.ai2b.crypto.instrument;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InstrumentRepository {
    private static final String SCOPE = "exchange = 'BINANCE' AND market_type = 'USD_M_FUTURES'";
    private final JdbcTemplate jdbc;

    public InstrumentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Page(List<Instrument> items, long total, int page, int size) { }

    @Transactional(readOnly = true)
    public Page list(String keyword, String status, int page, int size) {
        String where = SCOPE + " AND LOCATE(?, symbol) > 0 AND (? = '' OR status = ?)";
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM instrument WHERE " + where,
                Long.class, keyword, status, status);
        List<Instrument> items = jdbc.query("SELECT * FROM instrument WHERE " + where
                + " ORDER BY symbol LIMIT ? OFFSET ?", (rs, row) -> new Instrument(rs.getLong("id"),
                rs.getString("exchange"), rs.getString("market_type"), rs.getString("symbol"),
                rs.getString("base_asset"), rs.getString("quote_asset"), rs.getString("margin_asset"),
                rs.getString("contract_type"), rs.getString("status"), rs.getBigDecimal("price_tick_size"),
                rs.getBigDecimal("quantity_step_size"), rs.getTimestamp("updated_at").toInstant()),
                keyword, status, status, size, ((long) page - 1) * size);
        return new Page(items, total == null ? 0 : total, page, size);
    }

    @Transactional
    public void saveSnapshot(List<Instrument> snapshot, Instant now) {
        if (snapshot.isEmpty()) throw new IllegalArgumentException("Empty snapshot");
        Timestamp time = Timestamp.from(now);
        var remaining = new HashSet<>(jdbc.queryForList("SELECT symbol FROM instrument WHERE " + SCOPE, String.class));
        for (Instrument item : snapshot) {
            if (remaining.remove(item.symbol())) {
                jdbc.update("""
                        UPDATE instrument SET base_asset=?, quote_asset=?, margin_asset=?, contract_type=?,
                            status=?, price_tick_size=?, quantity_step_size=?, updated_at=?
                        WHERE exchange='BINANCE' AND market_type='USD_M_FUTURES' AND symbol=?
                        """, item.baseAsset(), item.quoteAsset(), item.marginAsset(), item.contractType(), item.status(),
                        item.priceTickSize(), item.quantityStepSize(), time, item.symbol());
            } else {
                jdbc.update("""
                        INSERT INTO instrument (exchange, market_type, symbol, base_asset, quote_asset, margin_asset,
                            contract_type, status, price_tick_size, quantity_step_size, created_at, updated_at)
                        VALUES ('BINANCE', 'USD_M_FUTURES', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, item.symbol(), item.baseAsset(), item.quoteAsset(), item.marginAsset(), item.contractType(),
                        item.status(), item.priceTickSize(), item.quantityStepSize(), time, time);
            }
        }
        // Absence is not proof of delisting. Preserve history with an explicit local status.
        for (String symbol : remaining) {
            jdbc.update("UPDATE instrument SET status='NOT_IN_SNAPSHOT', updated_at=? WHERE " + SCOPE + " AND symbol=?",
                    time, symbol);
        }
        int updated = jdbc.update("""
                UPDATE collector_checkpoint SET last_event_time=?, updated_at=?
                WHERE source='BINANCE' AND stream_key='USD_M_FUTURES:exchangeInfo'
                """, time, time);
        if (updated == 0) jdbc.update("""
                INSERT INTO collector_checkpoint (source, stream_key, last_event_time, created_at, updated_at)
                VALUES ('BINANCE', 'USD_M_FUTURES:exchangeInfo', ?, ?, ?)
                """, time, time, time);
    }

    public Instant lastSuccessAt() {
        var values = jdbc.query("""
                SELECT last_event_time FROM collector_checkpoint
                WHERE source='BINANCE' AND stream_key='USD_M_FUTURES:exchangeInfo'
                """, (rs, row) -> rs.getTimestamp(1).toInstant());
        return values.isEmpty() ? null : values.getFirst();
    }
}
