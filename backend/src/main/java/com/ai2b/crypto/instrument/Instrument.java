package com.ai2b.crypto.instrument;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

public record Instrument(Long id, String exchange, String marketType, String symbol, String baseAsset,
        String quoteAsset, String marginAsset, String contractType, String status,
        @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal priceTickSize,
        @JsonSerialize(using = PlainDecimalSerializer.class) BigDecimal quantityStepSize,
        Instant updatedAt) {
    public static class PlainDecimalSerializer extends JsonSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator output, SerializerProvider provider) throws IOException {
            output.writeString(value.stripTrailingZeros().toPlainString());
        }
    }
}
