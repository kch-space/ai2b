package com.ai2b.crypto.alert;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ai2b.crypto.kline.Kline;
import com.ai2b.crypto.kline.KlineCollector;
import com.ai2b.crypto.kline.KlineRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AlertEngine {
    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);
    private final JdbcTemplate jdbc;
    private final KlineRepository klines;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;
    private final Instant startedAt;
    private com.ai2b.crypto.integration.IntegrationService integrations;
    @org.springframework.beans.factory.annotation.Autowired
    public void integrations(com.ai2b.crypto.integration.IntegrationService integrations) { this.integrations = integrations; }

    public AlertEngine(JdbcTemplate jdbc, KlineRepository klines, ObjectMapper mapper,
            TransactionTemplate tx, Clock clock, @Value("${market.alerts.enabled:true}") boolean enabled) {
        this.jdbc = jdbc; this.klines = klines; this.mapper = mapper; this.tx = tx; this.clock = clock; this.enabled = enabled; this.startedAt = clock.instant();
    }

    public List<PriceRule> rules() {
        return jdbc.query("SELECT * FROM alert_rule_version ORDER BY id", (rs, row) -> new PriceRule(rs.getLong("id"),
                rs.getString("rule_code"), rs.getInt("version_number"), rs.getString("direction"), rs.getInt("window_minutes"),
                rs.getBigDecimal("trigger_ratio"), rs.getBigDecimal("escalation_ratio"), rs.getBigDecimal("recovery_ratio"),
                rs.getInt("recovery_windows"), rs.getInt("cooldown_seconds"), rs.getBigDecimal("reminder_delta")));
    }

    @Scheduled(initialDelayString = "${market.alerts.initial-delay-ms:25000}",
            fixedDelayString = "${market.alerts.poll-interval-ms:5000}")
    public void scheduledEvaluate() {
        if (!enabled) return;
        for (String symbol : KlineCollector.SYMBOLS) {
            try { evaluate(symbol, clock.instant()); }
            catch (RuntimeException exception) { log.warn("Price alert evaluation failed for {} ({})", symbol, exception.getClass().getSimpleName()); }
        }
    }

    /** One instrument row lock serializes first-event creation, state/history/outbox and repeat windows. */
    public void evaluate(String symbol, Instant now) {
        KlineCollector.requireSupported(symbol);
        tx.executeWithoutResult(ignored -> {
            // Lock before any consistent read, including under MySQL REPEATABLE_READ.
            var instruments = jdbc.queryForList("""
                    SELECT id,status FROM instrument WHERE exchange='BINANCE' AND market_type='USD_M_FUTURES'
                    AND contract_type='PERPETUAL' AND quote_asset='USDT' AND margin_asset='USDT' AND symbol=? FOR UPDATE
                    """, symbol);
            if (instruments.isEmpty()) return;
            long instrumentId = ((Number) instruments.getFirst().get("id")).longValue();
            String instrumentStatus = (String) instruments.getFirst().get("status");
            Instant target = KlineCollector.latestClosedOpen(now.minusSeconds(2));
            List<Kline> bars = klines.list(instrumentId, target.minusSeconds(300), target);
            int liveBars = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM market_kline_live WHERE instrument_id=? AND open_time BETWEEN ? AND ?
                    AND observed_at>=? AND observed_at<=?
                    """, Integer.class, instrumentId, ts(target.minusSeconds(300)), ts(target), ts(startedAt), ts(now));
            for (PriceRule rule : rules()) {
                var decision = rule.evaluate(bars, target, now, liveBars == 6);
                if (!instrumentStatus.equals("TRADING")) decision = new PriceRule.Decision("UNKNOWN", "NOT_TRADING",
                        null, null, null, Map.of("quality", "NOT_TRADING"));
                apply(instrumentId, symbol, rule, decision, target, now);
            }
        });
    }

    private void apply(long instrumentId, String symbol, PriceRule rule, PriceRule.Decision decision, Instant target, Instant now) {
        var states = jdbc.queryForList("SELECT * FROM alert_rule_state WHERE instrument_id=? AND rule_version_id=?", instrumentId, rule.id());
        if (states.isEmpty()) {
            jdbc.update("""
                    INSERT INTO alert_rule_state(instrument_id,rule_version_id,outcome,reason,evaluated_at,recovery_count)
                    VALUES (?,?,'UNKNOWN','WARMUP',?,0)
                    """, instrumentId, rule.id(), ts(now));
            states = jdbc.queryForList("SELECT * FROM alert_rule_state WHERE instrument_id=? AND rule_version_id=?", instrumentId, rule.id());
        }
        Map<String, Object> state = states.getFirst();
        Instant last = instant(state.get("last_window"));
        if (decision.outcome().equals("UNKNOWN")) {
            jdbc.update("""
                    UPDATE alert_rule_state SET outcome='UNKNOWN',reason=?,evaluated_at=?,change_ratio=NULL
                    WHERE instrument_id=? AND rule_version_id=?
                    """, decision.reason(), ts(now), instrumentId, rule.id());
            return; // Never resolve on gaps, stale or historical observations.
        }
        if (last != null && !target.isAfter(last)) {
            if (target.equals(last)) jdbc.update("""
                    UPDATE alert_rule_state SET outcome=?,reason=?,evaluated_at=?,change_ratio=?
                    WHERE instrument_id=? AND rule_version_id=?
                    """, decision.outcome(), decision.reason(), ts(now), decision.changeRatio(), instrumentId, rule.id());
            return; // Restore observed quality without counting the same window twice.
        }
        int recovery = last != null && target.equals(last.plusSeconds(60))
                ? ((Number) state.get("recovery_count")).intValue() : 0;
        String fingerprint = symbol + ":" + rule.code() + ":" + rule.direction();
        var active = jdbc.queryForList("SELECT * FROM alert_event WHERE active_fingerprint=?", fingerprint);
        String evidence = json(decision.evidence());
        if (decision.outcome().equals("MATCH")) {
            recovery = 0;
            if (active.isEmpty()) {
                String id = UUID.randomUUID().toString();
                jdbc.update("""
                        INSERT INTO alert_event(id,instrument_id,rule_version_id,fingerprint,active_fingerprint,status,severity,
                        opened_at,last_seen_at,hit_count,evidence_json,last_notified_at,last_notified_ratio)
                        VALUES (?,?,?,?,?,'ACTIVE',?,?,?,1,?,?,?)
                        """, id, instrumentId, rule.id(), fingerprint, fingerprint, decision.severity(), ts(now), ts(now),
                        evidence, ts(now), decision.directionalRatio());
                history(id, "OPEN", decision.severity(), target, now, evidence, true);
            } else {
                var event = active.getFirst();
                String id = (String) event.get("id");
                String severity = (String) event.get("severity");
                String action = "UPDATE";
                boolean notify = false;
                if (severity.equals("P1") && decision.severity().equals("P0")) {
                    severity = "P0"; action = "ESCALATE"; notify = true;
                } else if (!now.isBefore(instant(event.get("last_notified_at")).plusSeconds(rule.cooldownSeconds()))
                        && decision.directionalRatio().subtract((BigDecimal) event.get("last_notified_ratio"))
                        .compareTo(rule.reminderDelta()) >= 0) {
                    action = "REMIND"; notify = true;
                }
                jdbc.update("UPDATE alert_event SET severity=?,last_seen_at=?,hit_count=hit_count+1,evidence_json=? WHERE id=?",
                        severity, ts(now), evidence, id);
                if (notify) jdbc.update("UPDATE alert_event SET last_notified_at=?,last_notified_ratio=? WHERE id=?",
                        ts(now), decision.directionalRatio(), id);
                history(id, action, severity, target, now, evidence, notify);
            }
        } else if (!active.isEmpty()) {
            recovery = decision.directionalRatio().compareTo(rule.recoveryRatio()) <= 0 ? recovery + 1 : 0;
            if (recovery >= rule.recoveryWindows()) {
                var event = active.getFirst();
                String id = (String) event.get("id");
                jdbc.update("""
                        UPDATE alert_event SET status='RESOLVED',active_fingerprint=NULL,resolved_at=?,last_seen_at=?,evidence_json=? WHERE id=?
                        """, ts(now), ts(now), evidence, id);
                history(id, "RESOLVE", (String) event.get("severity"), target, now, evidence, true);
                recovery = 0;
            }
        } else recovery = 0;
        jdbc.update("""
                UPDATE alert_rule_state SET last_window=?,outcome=?,reason=?,evaluated_at=?,recovery_count=?,change_ratio=?
                WHERE instrument_id=? AND rule_version_id=?
                """, ts(target), decision.outcome(), decision.reason(), ts(now), recovery, decision.changeRatio(), instrumentId, rule.id());
    }

    private void history(String eventId, String action, String severity, Instant target, Instant now, String evidence, boolean notify) {
        String historyId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO alert_event_history(id,event_id,action,severity,data_time,created_at,evidence_json) VALUES (?,?,?,?,?,?,?)
                """, historyId, eventId, action, severity, ts(target.plusMillis(Kline.MINUTE - 1)), ts(now), evidence);
        if (notify) {
            String id = UUID.randomUUID().toString();
            String payload = json(Map.of("simulation", true, "eventId", eventId, "action", action, "severity", severity,
                    "evidence", readJson(evidence)));
            jdbc.update("""
                    INSERT INTO notification_outbox(id,event_id,history_id,dedupe_key,channel,recipient,payload_json,status,next_attempt_at,created_at)
                    VALUES (?,?,?,?,'MOCK','LOCAL',?,'PENDING',?,?)
                    """, id, eventId, historyId, eventId + ":" + historyId + ":MOCK:LOCAL", payload, ts(now), ts(now));
            if (integrations != null) {
                jdbc.queryForObject("SELECT version_number FROM integration_config WHERE kind='TELEGRAM' FOR UPDATE", Long.class);
                var config = integrations.config("TELEGRAM");
                if (config.enabled() && config.ciphertext() != null && (severity.equals("P0") || "P1".equals(config.settings().get("minimumSeverity")))) {
                    String recipient = (String) config.settings().get("chatId");
                    String symbol = jdbc.queryForObject("SELECT i.symbol FROM alert_event e JOIN instrument i ON i.id=e.instrument_id WHERE e.id=?", String.class, eventId);
                    String realPayload = json(Map.of("simulation", false, "symbol", symbol, "eventId", eventId, "action", action, "severity", severity, "evidence", readJson(evidence)));
                    jdbc.update("""
                            INSERT INTO notification_outbox(id,event_id,history_id,dedupe_key,channel,recipient,payload_json,status,next_attempt_at,created_at,config_version)
                            VALUES (?,?,?,?,'TELEGRAM',?,?,'PENDING',?,?,?)
                            """, UUID.randomUUID().toString(), eventId, historyId, eventId + ":" + historyId + ":TELEGRAM:" + recipient,
                            recipient, realPayload, ts(now), ts(now), config.version());
                }
            }
        }
    }

    private Object readJson(String value) {
        try { return mapper.readValue(value, Object.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored evidence", e); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize alert evidence", e); }
    }
    static Timestamp ts(Instant value) { return Timestamp.from(value); }
    static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        // MySQL Connector/J may return DATETIME as LocalDateTime; session/storage is UTC.
        if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        throw new IllegalStateException("Unsupported database timestamp type");
    }
}
