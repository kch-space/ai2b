package com.ai2b.crypto.instrument;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class InstrumentSyncService {
    private final BinanceInstrumentClient client;
    private final InstrumentRepository repository;
    private final boolean enabled;
    private final long cooldownSeconds;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Instant lastAttemptAt;
    private volatile Instant nextAllowedAt = Instant.EPOCH;
    private volatile String outcome = "IDLE";
    private volatile String error;

    public InstrumentSyncService(BinanceInstrumentClient client, InstrumentRepository repository,
            @Value("${market.instruments.sync-enabled:true}") boolean enabled,
            @Value("${market.instruments.cooldown-seconds:60}") long cooldownSeconds) {
        this.client = client;
        this.repository = repository;
        this.enabled = enabled;
        this.cooldownSeconds = cooldownSeconds;
    }

    public record SyncStatus(String outcome, boolean running, Instant lastSuccessAt, Instant lastAttemptAt,
            Instant nextAllowedAt, String error) { }

    public SyncStatus status() {
        return new SyncStatus(outcome, running.get(), repository.lastSuccessAt(), lastAttemptAt, nextAllowedAt, error);
    }

    public SyncStatus sync() {
        if (!running.compareAndSet(false, true)) throw new SyncBusyException("合约同步正在进行", 1);
        try {
            Instant now = Instant.now();
            if (now.isBefore(nextAllowedAt)) {
                throw new SyncBusyException("同步过于频繁，请稍后再试",
                        Math.max(1, nextAllowedAt.getEpochSecond() - now.getEpochSecond() + 1));
            }
            lastAttemptAt = now;
            outcome = "RUNNING";
            error = null;
            try {
                var snapshot = client.fetch();
                repository.saveSnapshot(snapshot, Instant.now());
                outcome = "SUCCESS";
            } catch (MarketDataException exception) {
                error = exception.getMessage();
                outcome = "ERROR";
                nextAllowedAt = Instant.now().plusSeconds(Math.max(cooldownSeconds, exception.retryAfterSeconds()));
                throw exception;
            } catch (RuntimeException exception) {
                error = "合约数据保存失败，本次更新已回滚";
                outcome = "ERROR";
                nextAllowedAt = Instant.now().plusSeconds(cooldownSeconds);
                throw new MarketDataException(error, cooldownSeconds);
            }
            nextAllowedAt = Instant.now().plusSeconds(cooldownSeconds);
        } finally {
            running.set(false);
        }
        return status();
    }

    @Scheduled(initialDelayString = "${market.instruments.initial-delay-ms:5000}",
            fixedDelayString = "${market.instruments.sync-interval-ms:3600000}")
    public void scheduledSync() {
        if (!enabled) return;
        try { sync(); }
        catch (MarketDataException | SyncBusyException ignored) {
            // Retain the public failure state; scheduled failures must not stop the application.
        }
    }

    public static class SyncBusyException extends RuntimeException {
        final long retryAfterSeconds;
        SyncBusyException(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }
}
