package com.ai2b.crypto.metrics;

public class MetricException extends RuntimeException {
    public final String code;
    public final long retrySeconds;
    public final boolean sourceWide;
    public MetricException(String code, String message, long retrySeconds, boolean sourceWide) {
        super(message); this.code = code; this.retrySeconds = retrySeconds; this.sourceWide = sourceWide;
    }
}
