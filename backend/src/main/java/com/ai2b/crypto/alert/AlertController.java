package com.ai2b.crypto.alert;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean enabled;
    private com.ai2b.crypto.integration.IntegrationService integrations;
    @org.springframework.beans.factory.annotation.Autowired
    public void integrations(com.ai2b.crypto.integration.IntegrationService value) { integrations = value; }
    private boolean telegramEnabled() { return integrations != null && integrations.config("TELEGRAM").enabled(); }
    public AlertController(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
            @Value("${market.alerts.enabled:true}") boolean enabled) {
        this.jdbc = jdbc; this.mapper = mapper; this.clock = clock; this.enabled = enabled;
    }

    public record Page(List<Map<String, Object>> items, long total, int page, int pageSize,
            String channel, boolean simulation, String serverTime) { }

    @GetMapping("/events")
    public Page events(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "ALL") String status) {
        if (page < 1 || page > 100000 || pageSize < 1 || pageSize > 100 || !List.of("ALL", "ACTIVE", "RESOLVED").contains(status)) {
            throw new IllegalArgumentException();
        }
        String where = status.equals("ALL") ? "" : " WHERE e.status=?";
        Object[] args = status.equals("ALL") ? new Object[] {} : new Object[] {status};
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM alert_event e" + where, Long.class, args);
        var params = new java.util.ArrayList<>(List.of(args));
        params.add(pageSize); params.add((page - 1) * pageSize);
        var items = rows("""
                SELECT e.id,i.symbol,r.rule_code,r.version_number,e.status,e.severity,e.opened_at,e.last_seen_at,
                e.resolved_at,e.hit_count,e.evidence_json FROM alert_event e
                JOIN instrument i ON i.id=e.instrument_id JOIN alert_rule_version r ON r.id=e.rule_version_id
                """ + where + " ORDER BY e.last_seen_at DESC,e.id DESC LIMIT ? OFFSET ?", params.toArray());
        return new Page(items, total, page, pageSize, telegramEnabled() ? "MOCK+TELEGRAM" : "MOCK", !telegramEnabled(), clock.instant().toString());
    }

    @GetMapping("/events/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        try { UUID.fromString(id); } catch (RuntimeException e) { throw new MissingEvent(); }
        var events = rows("""
                SELECT e.id,i.symbol,r.rule_code,r.version_number,e.status,e.severity,e.opened_at,e.last_seen_at,
                e.resolved_at,e.hit_count,e.evidence_json FROM alert_event e
                JOIN instrument i ON i.id=e.instrument_id JOIN alert_rule_version r ON r.id=e.rule_version_id WHERE e.id=?
                """, id);
        if (events.isEmpty()) throw new MissingEvent();
        return Map.of("event", events.getFirst(), "history", rows("""
                SELECT id,action,severity,data_time,created_at,evidence_json FROM alert_event_history
                WHERE event_id=? ORDER BY data_time,created_at,id
                """, id), "notifications", rows("""
                SELECT id,history_id,channel,status,attempts,next_attempt_at,lease_until,last_error,provider_message_id,
                created_at,sent_at FROM notification_outbox WHERE event_id=? ORDER BY created_at,id
                """, id), "attempts", rows("""
                SELECT a.outbox_id,a.attempt_number,a.outcome,a.error_message,a.created_at FROM notification_attempt a
                JOIN notification_outbox o ON o.id=a.outbox_id WHERE o.event_id=? ORDER BY a.created_at,a.id
                """, id), "simulation", !telegramEnabled());
    }

    @GetMapping("/rules")
    public Map<String, Object> rules() {
        return Map.of("items", rows("SELECT * FROM alert_rule_version ORDER BY id"), "states", rows("""
                SELECT i.symbol,r.rule_code,s.outcome,s.reason,s.last_window,s.evaluated_at,s.recovery_count,s.change_ratio
                FROM alert_rule_state s JOIN instrument i ON i.id=s.instrument_id
                JOIN alert_rule_version r ON r.id=s.rule_version_id ORDER BY i.symbol,r.id
                """), "enabled", enabled, "simulation", !telegramEnabled());
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args).stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>();
            row.forEach((key, value) -> {
                key = key.toLowerCase(Locale.ROOT);
                if (value instanceof Timestamp timestamp) value = timestamp.toInstant().toString();
                if (value instanceof LocalDateTime local) value = local.toInstant(ZoneOffset.UTC).toString();
                if (value instanceof BigDecimal decimal) value = decimal.toPlainString();
                if (key.equals("evidence_json")) {
                    key = "evidence";
                    try { value = mapper.readValue((String) value, Object.class); }
                    catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored alert evidence", e); }
                }
                result.put(key, value);
            });
            return result;
        }).toList();
    }
    private static class MissingEvent extends RuntimeException { }
    @ExceptionHandler(MissingEvent.class)
    public ResponseEntity<?> missing() { return ResponseEntity.status(404).body(Map.of("message", "事件不存在")); }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("message", "分页或状态参数无效")); }
}
