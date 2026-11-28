package com.ai2b.crypto.metrics;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MetricRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public MetricRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public record Sync(Instant lastAttemptAt, Instant lastSuccessAt, Instant nextAllowedAt,
            int failures, String errorCode, String error) { }
    public record Gate(Instant until, String error) { }

    public Sync sync(long instrumentId, MetricType type) {
        return jdbc.query("""
                SELECT * FROM market_metric_sync WHERE instrument_id=? AND metric_type=?
                """, (rs, row) -> new Sync(rs.getTimestamp("last_attempt_at").toInstant(),
                rs.getTimestamp("last_success_at") == null ? null : rs.getTimestamp("last_success_at").toInstant(),
                rs.getTimestamp("next_allowed_at").toInstant(), rs.getInt("failures"),
                rs.getString("error_code"), rs.getString("error_message")), instrumentId, type.name())
                .stream().findFirst().orElse(new Sync(null, null, Instant.EPOCH, 0, null, null));
    }

    public Gate sourceGate() {
        return jdbc.query("""
                SELECT next_allowed_at,error_message FROM market_metric_sync
                WHERE error_code IN ('RATE_LIMIT','NETWORK_UNAVAILABLE','UPSTREAM_UNAVAILABLE')
                ORDER BY next_allowed_at DESC LIMIT 1
                """, (rs, row) -> new Gate(rs.getTimestamp(1).toInstant(), rs.getString(2)))
                .stream().findFirst().orElse(new Gate(Instant.EPOCH, null));
    }

    public List<MetricPoint> list(long instrumentId, MetricType type, Instant start, Instant end) {
        return jdbc.query("""
                SELECT event_time,received_at,values_json FROM market_metric WHERE instrument_id=? AND metric_type=?
                AND event_time BETWEEN ? AND ? ORDER BY event_time
                """, (rs, row) -> {
                    try { return new MetricPoint(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(),
                            mapper.readValue(rs.getString(3), new TypeReference<Map<String, String>>() { })); }
                    catch (Exception exception) { throw new IllegalStateException("Stored metric data is invalid"); }
                }, instrumentId, type.name(), Timestamp.from(start), Timestamp.from(end));
    }

    @Transactional
    public void save(long instrumentId, MetricType type, List<MetricPoint> points, Instant now, Instant next) {
        if (!points.isEmpty()) {
            Instant first = points.stream().map(MetricPoint::eventTime).min(Instant::compareTo).orElseThrow();
            Instant last = points.stream().map(MetricPoint::eventTime).max(Instant::compareTo).orElseThrow();
            var existing = new java.util.HashSet<>(jdbc.query("""
                    SELECT event_time FROM market_metric WHERE instrument_id=? AND metric_type=? AND event_time BETWEEN ? AND ?
                    """, (rs, row) -> rs.getTimestamp(1).toInstant(), instrumentId, type.name(), Timestamp.from(first), Timestamp.from(last)));
            var unique = new java.util.TreeMap<Instant, MetricPoint>();
            for (var point : points) unique.putIfAbsent(point.eventTime(), point);
            var fresh = unique.values().stream().filter(point -> !existing.contains(point.eventTime())).toList();
            jdbc.batchUpdate("""
                    INSERT INTO market_metric (instrument_id,metric_type,event_time,received_at,values_json) VALUES (?,?,?,?,?)
                    """, fresh, 500, (ps, point) -> {
                        ps.setLong(1, instrumentId); ps.setString(2, type.name()); ps.setTimestamp(3, Timestamp.from(point.eventTime()));
                        ps.setTimestamp(4, Timestamp.from(point.receivedAt()));
                        try { ps.setString(5, mapper.writeValueAsString(point.values())); }
                        catch (Exception exception) { throw new IllegalStateException("Metric serialization failed"); }
                    });
        }
        writeSync(instrumentId, type, new Sync(now, now, next, 0, null, null));
    }

    @Transactional
    public void fail(long instrumentId, MetricType type, Instant now, Instant next, int failures, MetricException error) {
        Sync old = sync(instrumentId, type);
        writeSync(instrumentId, type, new Sync(now, old.lastSuccessAt(), next, failures, error.code, error.getMessage()));
    }

    private void writeSync(long id, MetricType type, Sync sync) {
        Timestamp attempted = Timestamp.from(sync.lastAttemptAt());
        Timestamp success = sync.lastSuccessAt() == null ? null : Timestamp.from(sync.lastSuccessAt());
        Timestamp next = Timestamp.from(sync.nextAllowedAt());
        int count = jdbc.update("""
                UPDATE market_metric_sync SET last_attempt_at=?,last_success_at=?,next_allowed_at=?,failures=?,error_code=?,error_message=?
                WHERE instrument_id=? AND metric_type=?
                """, attempted, success, next, sync.failures(), sync.errorCode(), sync.error(), id, type.name());
        if (count == 0) jdbc.update("""
                INSERT INTO market_metric_sync (last_attempt_at,last_success_at,next_allowed_at,failures,error_code,error_message,instrument_id,metric_type)
                VALUES (?,?,?,?,?,?,?,?)
                """, attempted, success, next, sync.failures(), sync.errorCode(), sync.error(), id, type.name());
    }
}
