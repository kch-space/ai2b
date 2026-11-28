package com.ai2b.crypto.metrics;

import java.time.Instant;
import java.util.Map;

/** Decimal values remain exact strings; the type determines fields and units. */
public record MetricPoint(Instant eventTime, Instant receivedAt, Map<String, String> values) {
    public MetricPoint { values = Map.copyOf(values); }
}
