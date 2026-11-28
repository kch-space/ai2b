package com.ai2b.crypto.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.ai2b.crypto.kline.Kline;
import org.junit.jupiter.api.Test;

class PriceRuleTest {
    private final Instant target = Instant.parse("2026-11-28T01:00:00Z");
    static PriceRule rule(String direction) {
        return new PriceRule(1, "PRICE_DROP", 1, direction, 5, new BigDecimal("0.05"),
                new BigDecimal("0.10"), new BigDecimal("0.02"), 2, 900, new BigDecimal("0.01"));
    }
    private List<Kline> bars(String last) {
        var bars = new ArrayList<Kline>();
        for (int i = 0; i < 6; i++) {
            Instant open = target.minusSeconds((5 - i) * 60L);
            BigDecimal price = new BigDecimal(i == 5 ? last : "100");
            bars.add(new Kline(open, open.plusMillis(59999), price, price, price, price,
                    BigDecimal.ONE, BigDecimal.ONE, 1, BigDecimal.ZERO, BigDecimal.ZERO, target.plusSeconds(62)));
        }
        return bars;
    }
    @Test void preciseInclusiveThresholdsAndBothDirections() {
        var drop = rule("DOWN");
        assertThat(drop.evaluate(bars("95"), target, target.plusSeconds(65), true).outcome()).isEqualTo("MATCH");
        assertThat(drop.evaluate(bars("90"), target, target.plusSeconds(65), true).severity()).isEqualTo("P0");
        assertThat(drop.evaluate(bars("95.0000000000000001"), target, target.plusSeconds(65), true).outcome()).isEqualTo("NO_MATCH");
        assertThat(rule("UP").evaluate(bars("110"), target, target.plusSeconds(65), true).severity()).isEqualTo("P0");
        assertThat(drop.evaluate(bars("95"), target, target.plusSeconds(65), true).evidence().get("changeRatio"))
                .isEqualTo("-0.050000000000000000");
    }
    @Test void insufficientGappedUnclosedStaleAndHistoricalAreUnknown() {
        var r = rule("DOWN");
        assertThat(r.evaluate(bars("80").subList(1, 6), target, target.plusSeconds(65), true).reason()).isEqualTo("MISSING_BARS");
        var gapped = bars("80"); gapped.set(2, gapped.get(1));
        assertThat(r.evaluate(gapped, target, target.plusSeconds(65), true).reason()).isEqualTo("GAP");
        assertThat(r.evaluate(bars("80"), target, target.plusSeconds(59), true).reason()).isEqualTo("UNCLOSED");
        assertThat(r.evaluate(bars("80"), target, target.plusSeconds(151), true).reason()).isEqualTo("STALE");
        assertThat(r.evaluate(bars("80"), target, target.plusSeconds(65), false).reason()).isEqualTo("BACKFILL_OR_WARMUP");
    }
}
