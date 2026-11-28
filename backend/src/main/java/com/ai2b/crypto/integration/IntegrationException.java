package com.ai2b.crypto.integration;

public class IntegrationException extends RuntimeException {
    public final int status;
    public final boolean permanent;
    public final long retrySeconds;
    public IntegrationException(int status, String message) { this(status, message, true, 0); }
    public IntegrationException(int status, String message, boolean permanent, long retrySeconds) {
        super(message); this.status = status; this.permanent = permanent; this.retrySeconds = retrySeconds;
    }
}
