package com.ai2b.crypto.kline;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ai2b.crypto.instrument.Instrument;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/v1/instruments/{symbol}/klines")
public class KlineController {
    private final KlineRepository repository;
    private final KlineCollector collector;
    private final Clock clock;

    public KlineController(KlineRepository repository, KlineCollector collector, Clock clock) {
        this.repository = repository;
        this.collector = collector;
        this.clock = clock;
    }

    public record Gap(Instant start, Instant end, int minutes) { }
    public record Response(Instrument instrument, String interval, Instant start, Instant end, List<Kline> items,
            int expectedBars, int missingBars, List<Gap> gaps, boolean stale, String quality,
            KlineCollector.Status collector, Instant serverTime, String requestId) { }
    public record SyncResponse(KlineCollector.Status collector, String requestId) { }
    public record ErrorResponse(String code, String message, String requestId) { }

    @GetMapping
    public Response list(@PathVariable String symbol, @RequestParam(defaultValue = "6") int hours) {
        KlineCollector.requireSupported(symbol);
        if (hours < 1 || hours > 24) throw new IllegalArgumentException();
        var instrument = repository.findInstrument(symbol).orElseThrow(() ->
                new KlineException("INSTRUMENT_NOT_READY", "尚无该合约基础信息，请先同步合约列表", 60));
        Instant now = clock.instant();
        Instant end = KlineCollector.latestClosedOpen(now.minusSeconds(2));
        Instant start = end.minusMillis((hours * 60L - 1) * Kline.MINUTE);
        var candles = repository.list(instrument.id(), start, end);
        var gaps = gaps(candles, start, end);
        int missing = gaps.stream().mapToInt(Gap::minutes).sum();
        boolean stale = candles.isEmpty() || candles.getLast().openTime().isBefore(end);
        return new Response(instrument, "1m", start, end, candles, hours * 60, missing, gaps, stale,
                candles.isEmpty() ? "UNAVAILABLE" : missing > 0 ? "GAPPED" : "COMPLETE", collector.status(symbol), now, requestId());
    }

    static List<Gap> gaps(List<Kline> candles, Instant start, Instant end) {
        List<Gap> gaps = new ArrayList<>();
        Instant cursor = start;
        for (Kline candle : candles) {
            if (candle.openTime().isAfter(cursor)) addGap(gaps, cursor, candle.openTime().minusMillis(Kline.MINUTE));
            cursor = candle.openTime().plusMillis(Kline.MINUTE);
        }
        if (!cursor.isAfter(end)) addGap(gaps, cursor, end);
        return gaps;
    }

    private static void addGap(List<Gap> gaps, Instant start, Instant end) {
        gaps.add(new Gap(start, end, Math.toIntExact((end.toEpochMilli() - start.toEpochMilli()) / Kline.MINUTE + 1)));
    }

    @PostMapping("/sync")
    public ResponseEntity<SyncResponse> trigger(@PathVariable String symbol,
            @RequestHeader(value = "X-AI2B-Action", defaultValue = "") String action) {
        if (!action.equals("collect-klines")) throw new IllegalArgumentException();
        return ResponseEntity.accepted().body(new SyncResponse(collector.trigger(symbol), requestId()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalid() {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_ARGUMENT", "请求参数不正确", requestId()));
    }

    @ExceptionHandler(KlineException.class)
    public ResponseEntity<ErrorResponse> error(KlineException exception) {
        int status = exception.code.equals("UNSUPPORTED_SYMBOL") ? 404
                : exception.code.equals("COLLECTOR_BUSY") ? 429 : 503;
        return ResponseEntity.status(status).header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds))
                .body(new ErrorResponse(exception.code, exception.getMessage(), requestId()));
    }

    private String requestId() { return UUID.randomUUID().toString(); }
}
