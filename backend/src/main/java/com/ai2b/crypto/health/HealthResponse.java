package com.ai2b.crypto.health;

import java.time.Instant;

public record HealthResponse(String status, String service, Instant timestamp, String requestId) {
}
