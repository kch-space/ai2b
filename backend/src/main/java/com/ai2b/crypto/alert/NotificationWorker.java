package com.ai2b.crypto.alert;

import static com.ai2b.crypto.alert.AlertEngine.instant;
import static com.ai2b.crypto.alert.AlertEngine.ts;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationWorker {
    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);
    private static final int MAX_ATTEMPTS = 5;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MockNotificationChannel channel;
    private final Clock clock;
    private final boolean enabled;
    public record Job(String id, String token, String payload, int attempt) { }

    public NotificationWorker(JdbcTemplate jdbc, TransactionTemplate tx, MockNotificationChannel channel,
            Clock clock, @Value("${market.alerts.enabled:true}") boolean enabled) {
        this.jdbc = jdbc; this.tx = tx; this.channel = channel; this.clock = clock; this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${market.alerts.initial-delay-ms:25000}", fixedDelay = 2000)
    public void scheduledRun() {
        if (!enabled) return;
        try { for (int i = 0; i < 10 && runOne(); i++) { /* bounded batch */ } }
        catch (RuntimeException exception) { log.warn("Mock outbox worker failed ({})", exception.getClass().getSimpleName()); }
    }

    /** Claim commits before adapter invocation; an expired lease is reclaimable after restart. */
    public Job claim(Instant now) {
        return tx.execute(ignored -> {
            var rows = jdbc.queryForList("""
                    SELECT * FROM notification_outbox
                    WHERE channel='MOCK' AND ((status='PENDING' AND next_attempt_at<=?) OR (status='PROCESSING' AND lease_until<=?))
                    ORDER BY next_attempt_at,created_at,id LIMIT 1 FOR UPDATE
                    """, ts(now), ts(now));
            if (rows.isEmpty()) return null;
            var row = rows.getFirst();
            String id = (String) row.get("id");
            int attempt = ((Number) row.get("attempts")).intValue();
            if (row.get("status").equals("PROCESSING")) {
                audit(id, attempt, "EXPIRED", "Worker lease expired; previous simulation outcome unknown", now);
            }
            if (attempt >= MAX_ATTEMPTS) {
                jdbc.update("UPDATE notification_outbox SET status='DEAD',lease_token=NULL,lease_until=NULL,last_error=? WHERE id=?",
                        "Retry limit reached after expired lease", id);
                return null;
            }
            String token = UUID.randomUUID().toString();
            jdbc.update("""
                    UPDATE notification_outbox SET status='PROCESSING',attempts=attempts+1,lease_token=?,lease_until=? WHERE id=?
                    """, token, ts(now.plusSeconds(30)), id);
            return new Job(id, token, (String) row.get("payload_json"), attempt + 1);
        });
    }

    public boolean runOne() {
        Job job = claim(clock.instant());
        if (job == null) return false;
        try { complete(job, channel.simulate(job.id(), job.payload()), null, clock.instant()); }
        catch (Exception exception) {
            // Do not expose adapter exception text/payload in public logs or error fields.
            complete(job, null, "Mock simulation failed (" + exception.getClass().getSimpleName() + ")", clock.instant());
        }
        return true;
    }

    public void complete(Job job, String messageId, String error, Instant now) {
        tx.executeWithoutResult(ignored -> {
            var rows = jdbc.queryForList("SELECT * FROM notification_outbox WHERE id=? FOR UPDATE", job.id());
            if (rows.isEmpty()) return;
            var row = rows.getFirst();
            if (!row.get("status").equals("PROCESSING") || !job.token().equals(row.get("lease_token"))
                    || !now.isBefore(instant(row.get("lease_until")))) return; // Fenced stale worker.
            if (error == null) {
                jdbc.update("""
                        UPDATE notification_outbox SET status='SIMULATED',provider_message_id=?,sent_at=?,lease_token=NULL,lease_until=NULL WHERE id=?
                        """, messageId, ts(now), job.id());
                audit(job.id(), job.attempt(), "SIMULATED", null, now);
            } else {
                String safe = error.substring(0, Math.min(512, error.length()));
                jdbc.update("""
                        UPDATE notification_outbox SET status=?,next_attempt_at=?,last_error=?,lease_token=NULL,lease_until=NULL WHERE id=?
                        """, job.attempt() >= MAX_ATTEMPTS ? "DEAD" : "PENDING",
                        ts(now.plusSeconds(Math.min(300, 5L << (job.attempt() - 1)))), safe, job.id());
                audit(job.id(), job.attempt(), "FAILED", safe, now);
            }
        });
    }

    private void audit(String id, int attempt, String outcome, String error, Instant now) {
        jdbc.update("""
                INSERT INTO notification_attempt(id,outbox_id,attempt_number,outcome,error_message,created_at) VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), id, attempt, outcome, error, ts(now));
    }
}
