package com.ai2b.crypto.alert;

import static com.ai2b.crypto.alert.AlertEngine.ts;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.ai2b.crypto.instrument.Instrument;
import com.ai2b.crypto.kline.Kline;
import com.ai2b.crypto.kline.KlineRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired KlineRepository klines;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    private final Instant start = Instant.parse("2026-11-28T02:00:00Z");
    private Instrument instrument;
    private AlertEngine engine;
    @BeforeEach void setup() {
        cleanup();
        jdbc.update("""
                INSERT INTO instrument(exchange,market_type,symbol,base_asset,quote_asset,margin_asset,contract_type,status,
                price_tick_size,quantity_step_size,created_at,updated_at)
                VALUES ('BINANCE','USD_M_FUTURES','BTCUSDT','BTC','USDT','USDT','PERPETUAL','TRADING',0.01,0.001,?,?)
                """, ts(start), ts(start));
        instrument = klines.findInstrument("BTCUSDT").orElseThrow();
        engine = new AlertEngine(jdbc, klines, mapper, tx, Clock.fixed(start.minusSeconds(60), ZoneOffset.UTC), false);
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM notification_attempt"); jdbc.update("DELETE FROM notification_outbox");
        jdbc.update("DELETE FROM alert_event_history"); jdbc.update("DELETE FROM alert_event"); jdbc.update("DELETE FROM alert_rule_state");
        jdbc.update("DELETE FROM market_kline_live"); jdbc.update("DELETE FROM market_kline");
        jdbc.update("DELETE FROM collector_checkpoint"); jdbc.update("DELETE FROM market_metric_sync");
        jdbc.update("DELETE FROM market_metric"); jdbc.update("DELETE FROM instrument");
    }
    private Kline candle(Instant time, String value) {
        BigDecimal price = new BigDecimal(value);
        return new Kline(time, time.plusMillis(59999), price, price, price, price,
                BigDecimal.ONE, BigDecimal.ONE, 1, BigDecimal.ZERO, BigDecimal.ZERO, time.plusSeconds(62));
    }
    private void seed(String last, boolean live) {
        var candles = new ArrayList<Kline>();
        for (int i = 0; i < 6; i++) candles.add(candle(start.plusSeconds(i * 60L), i == 5 ? last : "100"));
        if (live) klines.saveLivePage(instrument, candles, start, start, start.plusSeconds(300), start.plusSeconds(362));
        else klines.savePage(instrument, candles, start, start, start.plusSeconds(300), start.plusSeconds(362));
    }
    private void next(int minute, String price) {
        Instant time = start.plusSeconds(minute * 60L);
        klines.saveLivePage(instrument, List.of(candle(time, price)), start, time, time, time.plusSeconds(62));
    }
    private void evaluate(int minute) { engine.evaluate("BTCUSDT", start.plusSeconds(minute * 60L + 65)); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private String eventId() { return jdbc.queryForObject("SELECT id FROM alert_event ORDER BY opened_at LIMIT 1", String.class); }

    @Test void openRepeatEscalateUnknownRecoverAndNewEventWithAtomicOutbox() {
        seed("94", true); evaluate(5); evaluate(5);
        assertThat(count("alert_event")).isEqualTo(1);
        assertThat(count("alert_event_history")).isEqualTo(1);
        assertThat(count("notification_outbox")).isEqualTo(1);
        next(6, "93"); evaluate(6);
        assertThat(count("notification_outbox")).isEqualTo(1);
        next(7, "89"); evaluate(7);
        assertThat(jdbc.queryForObject("SELECT severity FROM alert_event", String.class)).isEqualTo("P0");
        assertThat(count("notification_outbox")).isEqualTo(2); // Escalation bypasses cooldown.
        next(8, "100"); evaluate(8);
        // Early poll before next bar arrives is UNKNOWN, not a recovery or a reset of a valid window.
        engine.evaluate("BTCUSDT", start.plusSeconds(9 * 60L + 65));
        assertThat(jdbc.queryForObject("SELECT status FROM alert_event", String.class)).isEqualTo("ACTIVE");
        next(9, "100"); evaluate(9);
        assertThat(jdbc.queryForObject("SELECT status FROM alert_event", String.class)).isEqualTo("RESOLVED");
        assertThat(count("notification_outbox")).isEqualTo(3);
        next(10, "80"); evaluate(10);
        assertThat(count("alert_event")).isEqualTo(2);
        assertThat(count("notification_outbox")).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_event WHERE active_fingerprint IS NOT NULL", Long.class)).isEqualTo(1);
    }

    @Test void historicalStaleAndRestartWindowsNeverOpenOrResolve() {
        seed("80", false); evaluate(5);
        assertThat(count("alert_event")).isZero();
        assertThat(jdbc.queryForObject("SELECT reason FROM alert_rule_state WHERE rule_version_id=2", String.class)).isEqualTo("BACKFILL_OR_WARMUP");
        // Historical replays cannot retroactively promote existing rows to real-time.
        seed("80", true); evaluate(5);
        assertThat(count("market_kline_live")).isZero();
        assertThat(count("alert_event")).isZero();
        cleanup(); setup(); seed("94", true); evaluate(5);
        var restarted = new AlertEngine(jdbc, klines, mapper, tx,
                Clock.fixed(start.plusSeconds(363), ZoneOffset.UTC), false);
        next(6, "100"); restarted.evaluate("BTCUSDT", start.plusSeconds(425));
        assertThat(jdbc.queryForObject("SELECT reason FROM alert_rule_state WHERE rule_version_id=2", String.class)).isEqualTo("BACKFILL_OR_WARMUP");
        assertThat(jdbc.queryForObject("SELECT status FROM alert_event", String.class)).isEqualTo("ACTIVE");
        engine.evaluate("BTCUSDT", start.plusSeconds(1200));
        assertThat(jdbc.queryForObject("SELECT status FROM alert_event", String.class)).isEqualTo("ACTIVE");
    }

    @Test void consecutiveRecoveryGapAndHysteresis() {
        seed("94", true); evaluate(5);
        next(6, "100"); evaluate(6); // recovery=1
        next(7, "97"); evaluate(7); // 3% is not recovered; count resets.
        next(8, "100"); evaluate(8);
        next(10, "100"); evaluate(10); // missing minute 9: UNKNOWN
        assertThat(jdbc.queryForObject("SELECT status FROM alert_event", String.class)).isEqualTo("ACTIVE");
        // Backfilled missing bar cannot be live, so all windows containing it stay UNKNOWN.
        Instant missing = start.plusSeconds(540);
        klines.savePage(instrument, List.of(candle(missing, "100")), start, missing, missing, missing.plusSeconds(125));
        evaluate(10);
        assertThat(jdbc.queryForObject("SELECT status FROM alert_event", String.class)).isEqualTo("ACTIVE");
    }

    @Test void cooldownAndSignificantChangeRequiredForReminder() {
        seed("94", true); evaluate(5);
        for (int i = 6; i <= 20; i++) { next(i, "100"); }
        // Keep original event active by not evaluating intervening no-match windows.
        next(21, "94"); evaluate(21);
        assertThat(count("notification_outbox")).isEqualTo(1); // same 6%, even after 15m
        next(22, "92"); evaluate(22);
        assertThat(count("notification_outbox")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_event_history WHERE action='REMIND'", Long.class)).isEqualTo(1);
    }

    @Test void concurrentFirstHitsAndRecreatedEngineDeduplicate() throws Exception {
        seed("89", true);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var gate = new CountDownLatch(1);
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) tasks.add(pool.submit(() -> { gate.await(); evaluate(5); return null; }));
            gate.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertThat(count("alert_event")).isEqualTo(1); assertThat(count("notification_outbox")).isEqualTo(1);
        var recreated = new AlertEngine(jdbc, klines, mapper, tx, Clock.fixed(start.minusSeconds(60), ZoneOffset.UTC), false);
        recreated.evaluate("BTCUSDT", start.plusSeconds(365));
        assertThat(count("alert_event_history")).isEqualTo(1);
    }

    @Test void outboxFailureRollsBackEventHistoryAndEvaluationState() {
        seed("89", true);
        jdbc.execute("ALTER TABLE notification_outbox ADD CONSTRAINT test_reject CHECK (channel <> 'MOCK')");
        try {
            assertThatThrownBy(() -> evaluate(5)).isInstanceOf(RuntimeException.class);
            assertThat(count("alert_event")).isZero(); assertThat(count("alert_event_history")).isZero();
            assertThat(count("alert_rule_state")).isZero();
        } finally { jdbc.execute("ALTER TABLE notification_outbox DROP CONSTRAINT test_reject"); }
        evaluate(5); assertThat(count("alert_event")).isEqualTo(1);
    }

    @Test void workerLeaseRecoveryFencingRetryAndSimulationArePersisted() {
        seed("89", true); evaluate(5);
        Clock fixed = Clock.fixed(start.plusSeconds(366), ZoneOffset.UTC);
        var worker = new NotificationWorker(jdbc, tx, new MockNotificationChannel(mapper), fixed, false);
        var old = worker.claim(fixed.instant()); assertThat(old).isNotNull();
        assertThat(worker.claim(fixed.instant())).isNull();
        var reclaimed = worker.claim(fixed.instant().plusSeconds(31)); assertThat(reclaimed.attempt()).isEqualTo(2);
        worker.complete(old, "mock:old", null, fixed.instant().plusSeconds(32));
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox", String.class)).isEqualTo("PROCESSING");
        worker.complete(reclaimed, null, "test failure", fixed.instant().plusSeconds(32));
        assertThat(worker.claim(fixed.instant().plusSeconds(40))).isNull();
        var retry = worker.claim(fixed.instant().plusSeconds(43));
        worker.complete(retry, "mock:" + retry.id(), null, fixed.instant().plusSeconds(44));
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox", String.class)).isEqualTo("SIMULATED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM notification_outbox", String.class)).isEqualTo("test failure");
        assertThat(count("notification_attempt")).isEqualTo(3);
        assertThat(worker.claim(fixed.instant().plusSeconds(100))).isNull();
    }

    @Test void retryExhaustionAndRealWorkerNeverClaimsDelivery() {
        seed("89", true); evaluate(5);
        Instant now = start.plusSeconds(366);
        var worker = new NotificationWorker(jdbc, tx, new MockNotificationChannel(mapper), Clock.fixed(now, ZoneOffset.UTC), false);
        for (int i = 1; i <= 5; i++) {
            var job = worker.claim(now); assertThat(job.attempt()).isEqualTo(i);
            worker.complete(job, null, "simulated failure", now);
            now = now.plusSeconds(301);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox", String.class)).isEqualTo("DEAD");
        assertThat(worker.claim(now)).isNull();
        cleanup(); setup(); seed("89", true); evaluate(5);
        assertThat(worker.runOne()).isTrue(); assertThat(worker.runOne()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox", String.class)).isEqualTo("SIMULATED");
        assertThat(jdbc.queryForObject("SELECT provider_message_id FROM notification_outbox", String.class)).startsWith("mock:");
    }

    @Test void apiPaginationEvidenceHistoryAndErrors() throws Exception {
        seed("89", true); evaluate(5);
        mvc.perform(get("/api/v1/alerts/events")).andExpect(status().isOk()).andExpect(jsonPath("$.simulation").value(true))
                .andExpect(jsonPath("$.items[0].severity").value("P0")).andExpect(jsonPath("$.items[0].evidence.changeRatio").isString());
        mvc.perform(get("/api/v1/alerts/events/" + eventId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.history[0].action").value("OPEN")).andExpect(jsonPath("$.notifications[0].channel").value("MOCK"));
        mvc.perform(get("/api/v1/alerts/rules")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].trigger_ratio").isString());
        mvc.perform(get("/api/v1/alerts/events?page=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/alerts/events?status=invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/alerts/events?pageSize=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/alerts/events?page=wat")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/alerts/events/not-an-id")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/alerts/events?status=RESOLVED")).andExpect(jsonPath("$.total").value(0));
    }
}
