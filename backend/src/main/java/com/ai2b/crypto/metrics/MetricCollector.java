package com.ai2b.crypto.metrics;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ai2b.crypto.kline.KlineCollector;
import com.ai2b.crypto.kline.KlineRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MetricCollector {
    private final BinanceMetricClient client;
    private final MetricRepository repository;
    private final KlineRepository instruments;
    private final Executor executor;
    private final Clock clock;
    private final boolean enabled;
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
    public MetricCollector(BinanceMetricClient client, MetricRepository repository, KlineRepository instruments,
            @Qualifier("metricExecutor") Executor executor, Clock clock, @Value("${market.metrics.enabled:true}") boolean enabled) {
        this.client = client; this.repository = repository; this.instruments = instruments;
        this.executor = executor; this.clock = clock; this.enabled = enabled;
    }
    public boolean running(String symbol) { return running.computeIfAbsent(symbol, ignored -> new AtomicBoolean()).get(); }

    public void trigger(String symbol) {
        KlineCollector.requireSupported(symbol);
        var instrument = instruments.findInstrument(symbol).orElseThrow(() ->
                new MetricException("INSTRUMENT_NOT_READY", "尚无该合约基础信息，请先同步合约列表", 60, false));
        if (!instrument.status().equals("TRADING")) throw new MetricException("INSTRUMENT_NOT_TRADING", "该合约不在交易中，暂停指标采集", 300, false);
        var state = running.computeIfAbsent(symbol, ignored -> new AtomicBoolean());
        if (!state.compareAndSet(false, true)) throw busy(1);
        try {
            Instant now = clock.instant();
            var gate = repository.sourceGate();
            if (now.isBefore(gate.until())) throw busy(remaining(gate.until()));
            Instant earliest = Instant.MAX;
            for (MetricType type : MetricType.values()) {
                Instant next = repository.sync(instrument.id(), type).nextAllowedAt();
                if (next.isBefore(earliest)) earliest = next;
            }
            if (now.isBefore(earliest)) throw busy(remaining(earliest));
            executor.execute(() -> collect(symbol, instrument.id(), state));
        } catch (RuntimeException exception) {
            state.set(false);
            if (exception instanceof MetricException known) throw known;
            throw new MetricException("COLLECTOR_BUSY", "指标采集队列繁忙，请稍后重试", 10, false);
        }
    }

    private void collect(String symbol, long id, AtomicBoolean state) {
        try {
            for (MetricType type : MetricType.values()) {
                Instant now = clock.instant();
                var old = repository.sync(id, type);
                if (now.isBefore(old.nextAllowedAt())) continue;
                if (now.isBefore(repository.sourceGate().until())) break;
                Instant end = type.fiveMinute ? fiveMinuteEnd(now) : now;
                Instant start = type.fiveMinute ? end.minusSeconds(287 * 300L) : now.minusSeconds(86400);
                try {
                    var points = client.fetch(symbol, type, start, end, now);
                    Instant completed = clock.instant();
                    repository.save(id, type, points, completed, nextCollection(type, points.size(), completed));
                } catch (MetricException exception) {
                    failure(id, type, old, exception);
                    if (exception.sourceWide) break;
                } catch (RuntimeException exception) {
                    failure(id, type, old, new MetricException("PERSISTENCE_ERROR", "指标保存失败，本批已回滚，已有数据保留", 60, false));
                }
            }
        } finally { state.set(false); }
    }
    private void failure(long id, MetricType type, MetricRepository.Sync old, MetricException error) {
        int failures = Math.min(6, old.failures() + 1);
        long delay = Math.max(error.retrySeconds, Math.min(1800, 60L << (failures - 1)));
        Instant now = clock.instant(), next;
        try { next = now.plusSeconds(delay); }
        catch (java.time.DateTimeException | ArithmeticException exception) { next = Instant.parse("9999-12-31T23:59:59Z"); }
        repository.fail(id, type, now, next, failures, error);
    }
    public static Instant fiveMinuteEnd(Instant now) { return Instant.ofEpochMilli(Math.floorDiv(now.toEpochMilli(), 300000) * 300000); }
    static Instant nextCollection(MetricType type, int points, Instant now) {
        // Align to exchange buckets instead of drifting by request duration on every run.
        Instant regular = fiveMinuteEnd(now).plusSeconds(330);
        Instant repair = now.plusSeconds(60);
        return type.fiveMinute && points < 288 && repair.isBefore(regular) ? repair : regular;
    }
    private long remaining(Instant until) { return Math.max(1, until.getEpochSecond() - clock.instant().getEpochSecond() + 1); }
    private MetricException busy(long delay) { return new MetricException("COLLECTOR_BUSY", "指标采集中或处于冷却，请稍后刷新查看", delay, false); }

    @Scheduled(initialDelayString = "${market.metrics.initial-delay-ms:20000}", fixedDelayString = "${market.metrics.poll-interval-ms:30000}")
    public void scheduledCollect() {
        if (!enabled) return;
        for (String symbol : KlineCollector.SYMBOLS) {
            try { trigger(symbol); }
            catch (RuntimeException ignored) { /* Per-stream persisted state controls retry; independent from K-line jobs. */ }
        }
    }
}
