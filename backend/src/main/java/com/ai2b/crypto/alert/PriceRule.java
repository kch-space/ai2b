package com.ai2b.crypto.alert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.ai2b.crypto.kline.Kline;

/** Immutable rule snapshot. Every decision uses six consecutive, closed 1m bars. */
public record PriceRule(long id, String code, int version, String direction, int windowMinutes,
        BigDecimal triggerRatio, BigDecimal escalationRatio, BigDecimal recoveryRatio,
        int recoveryWindows, int cooldownSeconds, BigDecimal reminderDelta) {
    public record Decision(String outcome, String reason, BigDecimal changeRatio,
            BigDecimal directionalRatio, String severity, Map<String, Object> evidence) { }

    public Decision evaluate(List<Kline> bars, Instant target, Instant now, boolean liveWindow) {
        if (target.plusMillis(Kline.MINUTE).isAfter(now)) return unknown("UNCLOSED");
        if (now.toEpochMilli() - target.plusMillis(Kline.MINUTE).toEpochMilli() > 90_000) return unknown("STALE");
        if (bars.size() != windowMinutes + 1) return unknown("MISSING_BARS");
        for (int i = 0; i < bars.size(); i++) {
            Kline bar = bars.get(i);
            if (!bar.openTime().equals(target.minusMillis((windowMinutes - i) * Kline.MINUTE))) return unknown("GAP");
            if (!bar.closeTime().equals(bar.openTime().plusMillis(Kline.MINUTE - 1))
                    || !bar.receivedAt().isAfter(bar.closeTime()) || bar.close().signum() <= 0) return unknown("INVALID_BAR");
        }
        if (!liveWindow) return unknown("BACKFILL_OR_WARMUP");
        BigDecimal baseline = bars.getFirst().close();
        BigDecimal latest = bars.getLast().close();
        BigDecimal change = latest.subtract(baseline).divide(baseline, 18, RoundingMode.HALF_UP);
        BigDecimal directional = direction.equals("DOWN") ? change.negate() : change;
        String severity = directional.compareTo(escalationRatio) >= 0 ? "P0" : "P1";
        Map<String, Object> evidence = Map.ofEntries(
                Map.entry("source", "BINANCE_USD_M_CLOSED_1M"), Map.entry("quality", "COMPLETE_LIVE"),
                Map.entry("windowMinutes", windowMinutes), Map.entry("baselineTime", bars.getFirst().closeTime()),
                Map.entry("dataTime", bars.getLast().closeTime()), Map.entry("baselinePrice", baseline.toPlainString()),
                Map.entry("latestPrice", latest.toPlainString()), Map.entry("changeRatio", change.toPlainString()),
                Map.entry("triggerRatio", triggerRatio.toPlainString()), Map.entry("escalationRatio", escalationRatio.toPlainString()),
                Map.entry("recoveryRatio", recoveryRatio.toPlainString()), Map.entry("ruleVersion", version));
        return new Decision(directional.compareTo(triggerRatio) >= 0 ? "MATCH" : "NO_MATCH", "COMPLETE_LIVE",
                change, directional, severity, evidence);
    }

    private Decision unknown(String reason) {
        return new Decision("UNKNOWN", reason, null, null, null, Map.of("quality", reason));
    }
}
