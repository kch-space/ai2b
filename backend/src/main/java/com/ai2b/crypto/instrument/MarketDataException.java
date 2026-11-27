package com.ai2b.crypto.instrument;

public class MarketDataException extends RuntimeException {
    private final long retryAfterSeconds;

    public MarketDataException(String message) {
        this(message, 60);
    }

    public MarketDataException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
