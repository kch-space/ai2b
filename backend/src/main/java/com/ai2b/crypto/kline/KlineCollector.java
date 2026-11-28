package com.ai2b.crypto.kline;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class KlineCollector {
    public static final List<String> SYMBOLS = List.of("BTCUSDT", "ETHUSDT");
    private final BinanceKlineClient client;
    private final KlineRepository repository;
    private final Executor executor;
    private final Clock clock;
    private final boolean enabled;
    private final long cooldownSeconds;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final AtomicReference<Gate> sourceGate = new AtomicReference<>(new Gate(Instant.EPOCH, null));

    private static class State {
        final AtomicBoolean running = new AtomicBoolean();
        volatile String outcome = "IDLE";
        volatile String error;
        volatile Instant lastAttemptAt;
        volatile Instant nextAllowedAt = Instant.EPOCH;
        int failures;
        boolean warmed; // First successful collection after every process start is non-live.
    }
    private record Gate(Instant until, String error) { }
    public record Status(String outcome, boolean running, Instant lastAttemptAt, Instant nextAllowedAt,
            String error, Instant lastWriteAt, Instant coverageStart, Instant lastContiguousOpenTime) { }

    public KlineCollector(BinanceKlineClient client, KlineRepository repository,
            @Qualifier("klineExecutor") Executor executor, Clock clock,
            @Value("${market.klines.enabled:true}") boolean enabled,
            @Value("${market.klines.cooldown-seconds:10}") long cooldownSeconds) {
        this.client = client;
        this.repository = repository;
        this.executor = executor;
        this.clock = clock;
        this.enabled = enabled;
        this.cooldownSeconds = cooldownSeconds;
    }

    public static void requireSupported(String symbol) {
        if (!SYMBOLS.contains(symbol)) throw new KlineException("UNSUPPORTED_SYMBOL", "本阶段仅采集 BTCUSDT、ETHUSDT", 0);
    }

    public Status status(String symbol) {
        requireSupported(symbol);
        State state = states.computeIfAbsent(symbol, ignored -> new State());
        var checkpoint = repository.checkpoint(symbol).orElse(null);
        Gate gate = sourceGate.get();
        boolean gated = clock.instant().isBefore(gate.until());
        Instant next = state.nextAllowedAt.isAfter(gate.until()) ? state.nextAllowedAt : gate.until();
        return new Status(state.outcome, state.running.get(), state.lastAttemptAt, next,
                state.error == null && gated ? gate.error() : state.error,
                checkpoint == null ? null : checkpoint.lastWriteAt(), checkpoint == null ? null : checkpoint.start(),
                checkpoint == null ? null : checkpoint.contiguousOpen());
    }

    /** Dispatch a bounded job; HTTP callers do not wait for network or historical writes. */
    public Status trigger(String symbol) {
        return trigger(symbol, false); // Manual sync is always backfill, never real-time input.
    }

    private Status trigger(String symbol, boolean scheduled) {
        requireSupported(symbol);
        State state = states.computeIfAbsent(symbol, ignored -> new State());
        if (!state.running.compareAndSet(false, true)) throw busy("该合约正在采集中", 1);
        try {
            Instant until = state.nextAllowedAt.isAfter(sourceGate.get().until())
                    ? state.nextAllowedAt : sourceGate.get().until();
            if (clock.instant().isBefore(until)) throw busy("采集正在退避，请稍后刷新查看", remaining(until));
            state.outcome = "QUEUED";
            executor.execute(() -> collect(symbol, state, scheduled));
        } catch (RuntimeException exception) {
            state.running.set(false);
            if (exception instanceof KlineException known) throw known;
            state.outcome = "ERROR";
            state.error = "采集队列繁忙，请稍后重试";
            throw busy(state.error, 10);
        }
        return status(symbol);
    }

    private void collect(String symbol, State state, boolean scheduled) {
        state.lastAttemptAt = clock.instant();
        state.outcome = "RUNNING";
        state.error = null;
        try {
            Gate gate = sourceGate.get();
            if (clock.instant().isBefore(gate.until())) {
                throw new KlineException("UPSTREAM_COOLDOWN", gate.error(), remaining(gate.until()));
            }
            var instrument = repository.findInstrument(symbol).orElseThrow(() -> new KlineException(
                    "INSTRUMENT_NOT_READY", "尚无该合约基础信息，请先同步合约列表", 60));
            if (!instrument.status().equals("TRADING")) {
                throw new KlineException("INSTRUMENT_NOT_TRADING", "该合约当前不在交易中，暂停采集", 300);
            }
            Instant serverTime = client.serverTime();
            Instant now = clock.instant();
            if (Duration.between(serverTime, now).abs().compareTo(Duration.ofMinutes(5)) > 0) {
                throw new KlineException("CLOCK_SKEW", "本机与交易所时间偏差过大，暂停写入", 60);
            }
            Instant closedBefore = (serverTime.isBefore(now) ? serverTime : now).minusSeconds(2);
            Instant target = latestClosedOpen(closedBefore);
            var checkpoint = repository.checkpoint(symbol).orElse(null);
            Instant start = checkpoint == null ? target.minusMillis((1440L - 1) * Kline.MINUTE) : checkpoint.start();
            Instant cursor = checkpoint == null || checkpoint.contiguousOpen() == null
                    ? start : checkpoint.contiguousOpen().plusMillis(Kline.MINUTE);
            boolean live = scheduled && state.warmed && checkpoint != null
                    && target.minusMillis(Kline.MINUTE).equals(checkpoint.contiguousOpen())
                    && Duration.between(checkpoint.lastWriteAt(), now).abs().compareTo(Duration.ofSeconds(90)) <= 0;
            for (int batch = 0; batch < 4 && !cursor.isAfter(target); batch++) {
                Instant end = cursor.plusMillis((BinanceKlineClient.PAGE_SIZE - 1L) * Kline.MINUTE);
                if (end.isAfter(target)) end = target;
                var candles = client.fetch(symbol, cursor, end, closedBefore);
                Instant contiguous = live
                        ? repository.saveLivePage(instrument, candles, start, cursor, end, clock.instant())
                        : repository.savePage(instrument, candles, start, cursor, end, clock.instant());
                if (contiguous == null || contiguous.isBefore(end)) {
                    throw new KlineException("DATA_GAP", "采集区间存在缺失分钟，检查点已停在缺口前，将继续补齐", 30);
                }
                cursor = contiguous.plusMillis(Kline.MINUTE);
            }
            state.warmed = cursor.isAfter(target);
            state.outcome = cursor.isAfter(target) ? "SUCCESS" : "CATCHING_UP";
            state.failures = 0;
            state.nextAllowedAt = clock.instant().plusSeconds(cooldownSeconds);
            sourceGate.updateAndGet(old -> clock.instant().isBefore(old.until()) ? old : new Gate(Instant.EPOCH, null));
        } catch (KlineException exception) {
            fail(state, exception);
            if (exception.code.equals("UPSTREAM_RATE_LIMIT") || exception.code.equals("NETWORK_UNAVAILABLE")
                    || exception.code.equals("UPSTREAM_UNAVAILABLE")) {
                var next = new Gate(state.nextAllowedAt, exception.getMessage());
                sourceGate.updateAndGet(old -> old.until().isAfter(next.until()) ? old : next);
            }
        } catch (RuntimeException exception) {
            fail(state, new KlineException("PERSISTENCE_ERROR", "本批 K 线保存失败，已回滚，将从已提交检查点重试", 30));
        } finally { state.running.set(false); }
    }

    private void fail(State state, KlineException exception) {
        state.outcome = "ERROR";
        state.error = exception.getMessage();
        state.failures = Math.min(state.failures + 1, 6);
        long delay = Math.max(exception.retryAfterSeconds, Math.min(900, 30L << (state.failures - 1)));
        state.nextAllowedAt = deadline(Math.max(cooldownSeconds, delay));
    }

    /** Input already includes the settlement grace period. */
    public static Instant latestClosedOpen(Instant closedBefore) {
        return Instant.ofEpochMilli(Math.floorDiv(closedBefore.toEpochMilli(), Kline.MINUTE) * Kline.MINUTE - Kline.MINUTE);
    }

    private Instant deadline(long seconds) {
        try { return clock.instant().plusSeconds(seconds); }
        catch (java.time.DateTimeException | ArithmeticException exception) {
            return Instant.parse("9999-12-31T23:59:59Z");
        }
    }

    private long remaining(Instant until) { return Math.max(1, until.getEpochSecond() - clock.instant().getEpochSecond() + 1); }
    private KlineException busy(String message, long seconds) { return new KlineException("COLLECTOR_BUSY", message, seconds); }

    @Scheduled(initialDelayString = "${market.klines.initial-delay-ms:15000}",
            fixedDelayString = "${market.klines.poll-interval-ms:15000}")
    public void scheduledCollect() {
        if (!enabled) return;
        for (String symbol : SYMBOLS) {
            try { trigger(symbol, true); }
            catch (RuntimeException ignored) {
                // A queued job/cooldown or DB outage must not prevent the other symbol's next attempt.
            }
        }
    }
}
