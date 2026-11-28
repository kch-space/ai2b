package com.ai2b.crypto.kline;

public class KlineException extends RuntimeException {
    public final String code;
    public final long retryAfterSeconds;

    public KlineException(String code, String message, long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
