package com.ai2b.crypto.metrics;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.ai2b.crypto.kline.KlineCollector;
import com.ai2b.crypto.kline.KlineException;
import com.ai2b.crypto.kline.KlineRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/instruments/{symbol}/metrics")
public class MetricController {
    private final MetricRepository repository;
    private final KlineRepository instruments;
    private final MetricCollector collector;
    private final Clock clock;
    public MetricController(MetricRepository repository, KlineRepository instruments, MetricCollector collector, Clock clock) {
        this.repository = repository; this.instruments = instruments; this.collector = collector; this.clock = clock;
    }
    public record Stream(MetricType type, String authentication, String period, Instant start, Instant end,
            List<MetricPoint> items, MetricPoint latest, Integer expectedPoints, Integer missingPoints,
            boolean stale, String quality, MetricRepository.Sync sync) { }
    public record Response(String symbol, String baseAsset, String quoteAsset, boolean running,
            MetricRepository.Gate sourceGate, List<Stream> streams, Instant serverTime, String requestId) { }
    public record Error(String code, String message, String requestId) { }

    @GetMapping
    public Response list(@PathVariable String symbol) {
        KlineCollector.requireSupported(symbol);
        var instrument = instruments.findInstrument(symbol).orElseThrow(() ->
                new MetricException("INSTRUMENT_NOT_READY", "尚无该合约基础信息，请先同步合约列表", 60, false));
        Instant now = clock.instant();
        var streams = new ArrayList<Stream>();
        for (MetricType type : MetricType.values()) {
            Instant end = type.fiveMinute ? MetricCollector.fiveMinuteEnd(now) : now.plusSeconds(60);
            Instant start = type.fiveMinute ? end.minusSeconds(287 * 300L) : now.minusSeconds(86400);
            var points = repository.list(instrument.id(), type, start, end);
            var sync = repository.sync(instrument.id(), type);
            var latest = points.isEmpty() ? null : points.getLast();
            Integer missing = null;
            if (type.fiveMinute) {
                var times = new HashSet<>(points.stream().map(MetricPoint::eventTime).toList());
                int count = 0;
                for (Instant time = start; !time.isAfter(end); time = time.plusSeconds(300)) if (!times.contains(time)) count++;
                missing = count;
            }
            boolean stale = type == MetricType.FUNDING_HISTORY
                    ? sync.lastSuccessAt() == null || sync.lastSuccessAt().isBefore(now.minusSeconds(900))
                    : latest == null || latest.eventTime().isBefore(now.minusSeconds(900));
            String quality = latest == null ? "UNAVAILABLE" : stale ? "STALE" : missing != null && missing > 0 ? "GAPPED" : "READY";
            streams.add(new Stream(type, type.requiresKey ? "API_KEY" : "PUBLIC", type.fiveMinute ? "5m" : "OBSERVATION",
                    start, end, points, latest, type.fiveMinute ? 288 : null, missing, stale, quality, sync));
        }
        return new Response(symbol, instrument.baseAsset(), instrument.quoteAsset(), collector.running(symbol),
                repository.sourceGate(), streams, now, UUID.randomUUID().toString());
    }

    @PostMapping("/sync")
    public ResponseEntity<?> trigger(@PathVariable String symbol, @RequestHeader(value = "X-AI2B-Action", defaultValue = "") String action) {
        if (!action.equals("collect-metrics")) throw new IllegalArgumentException();
        collector.trigger(symbol);
        return ResponseEntity.accepted().body(java.util.Map.of("accepted", true, "requestId", UUID.randomUUID().toString()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Error> invalid() { return ResponseEntity.badRequest().body(new Error("INVALID_ARGUMENT", "请求参数不正确", UUID.randomUUID().toString())); }
    @ExceptionHandler(KlineException.class)
    public ResponseEntity<Error> unsupported(KlineException error) {
        return ResponseEntity.status(404).body(new Error(error.code, error.getMessage(), UUID.randomUUID().toString()));
    }
    @ExceptionHandler(MetricException.class)
    public ResponseEntity<Error> failure(MetricException error) {
        return ResponseEntity.status(error.code.equals("COLLECTOR_BUSY") ? 429 : 503)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(error.retrySeconds)).body(new Error(error.code, error.getMessage(), UUID.randomUUID().toString()));
    }
}
