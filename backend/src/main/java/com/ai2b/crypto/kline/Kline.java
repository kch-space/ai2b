package com.ai2b.crypto.kline;

import java.math.BigDecimal;
import java.time.Instant;

import com.ai2b.crypto.instrument.Instrument.PlainDecimalSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

public record Kline(Instant openTime, Instant closeTime,
        @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal open,
        @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal high,
        @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal low,
        @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal close,
        @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal volume,
        @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal quoteVolume,
        long trades,
        @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal takerBuyVolume,
        @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal takerBuyQuoteVolume,
        Instant receivedAt) {
    public static final long MINUTE = 60_000;
}
