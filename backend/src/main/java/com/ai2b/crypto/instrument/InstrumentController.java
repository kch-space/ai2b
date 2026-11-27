package com.ai2b.crypto.instrument;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/v1/instruments")
public class InstrumentController {
    private final InstrumentRepository repository;
    private final InstrumentSyncService sync;

    public InstrumentController(InstrumentRepository repository, InstrumentSyncService sync) {
        this.repository = repository;
        this.sync = sync;
    }

    public record ListResponse(java.util.List<Instrument> items, long total, int page, int size,
            InstrumentSyncService.SyncStatus sync, String requestId) { }
    public record SyncResponse(InstrumentSyncService.SyncStatus sync, String requestId) { }
    public record ErrorResponse(String code, String message, String requestId) { }

    @GetMapping
    public ListResponse list(@RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        String search = keyword.strip().toUpperCase(java.util.Locale.ROOT);
        if (!search.matches("[A-Z0-9_]{0,64}") || !status.matches("[A-Z_]{0,32}")
                || page < 1 || size < 1 || size > 200) throw new IllegalArgumentException();
        var result = repository.list(search, status, page, size);
        return new ListResponse(result.items(), result.total(), page, size, sync.status(), requestId());
    }

    @PostMapping("/sync")
    public SyncResponse synchronize(@RequestHeader(value = "X-AI2B-Action", defaultValue = "") String action) {
        // Same-origin custom header prevents cross-site form submissions; no CORS is enabled.
        if (!action.equals("sync-instruments")) throw new IllegalArgumentException();
        return new SyncResponse(sync.sync(), requestId());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalidInput() {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_ARGUMENT", "请求参数不正确", requestId()));
    }

    @ExceptionHandler(InstrumentSyncService.SyncBusyException.class)
    public ResponseEntity<ErrorResponse> busy(InstrumentSyncService.SyncBusyException exception) {
        return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds))
                .body(new ErrorResponse("SYNC_BUSY", exception.getMessage(), requestId()));
    }

    @ExceptionHandler(MarketDataException.class)
    public ResponseEntity<ErrorResponse> upstream(MarketDataException exception) {
        return ResponseEntity.status(503).header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(new ErrorResponse("SYNC_UNAVAILABLE", exception.getMessage(), requestId()));
    }

    private String requestId() { return UUID.randomUUID().toString(); }
}
