package com.ai2b.crypto.kline;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BinanceKlineClient {
    public static final int PAGE_SIZE = 499;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final String baseUrl;

    public BinanceKlineClient(ObjectMapper mapper, @Value("${market.binance.rest-base-url}") String baseUrl) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public Instant serverTime() {
        try {
            JsonNode value = mapper.readTree(get("/fapi/v1/time")).get("serverTime");
            if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
                throw new IllegalArgumentException();
            }
            return Instant.ofEpochMilli(value.longValue());
        } catch (KlineException exception) { throw exception; }
        catch (Exception exception) { throw invalid(); }
    }

    public List<Kline> fetch(String symbol, Instant start, Instant end, Instant closedBefore) {
        String body = get("/fapi/v1/klines?symbol=" + symbol + "&interval=1m&startTime=" + start.toEpochMilli()
                + "&endTime=" + (end.toEpochMilli() + Kline.MINUTE - 1) + "&limit=" + PAGE_SIZE);
        return parse(body, start, end, closedBefore, Instant.now());
    }

    List<Kline> parse(String body, Instant start, Instant end, Instant closedBefore, Instant receivedAt) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isArray() || root.size() > PAGE_SIZE) throw new IllegalArgumentException();
            var sorted = new TreeMap<Instant, Kline>();
            for (JsonNode row : root) {
                if (!row.isArray() || row.size() < 11) throw new IllegalArgumentException();
                long openMillis = integer(row.get(0));
                long closeMillis = integer(row.get(6));
                if (openMillis < 0 || openMillis % Kline.MINUTE != 0 || closeMillis != openMillis + Kline.MINUTE - 1) {
                    throw new IllegalArgumentException();
                }
                Instant openTime = Instant.ofEpochMilli(openMillis);
                Instant closeTime = Instant.ofEpochMilli(closeMillis);
                if (!closeTime.isBefore(closedBefore) || !closeTime.isBefore(receivedAt)) continue;
                if (openTime.isBefore(start) || openTime.isAfter(end)) throw new IllegalArgumentException();
                BigDecimal open = decimal(row.get(1), true), high = decimal(row.get(2), true);
                BigDecimal low = decimal(row.get(3), true), close = decimal(row.get(4), true);
                BigDecimal volume = decimal(row.get(5), false), quoteVolume = decimal(row.get(7), false);
                long trades = integer(row.get(8));
                BigDecimal buyVolume = decimal(row.get(9), false), buyQuote = decimal(row.get(10), false);
                if (trades < 0 || high.compareTo(low) < 0 || open.compareTo(low) < 0 || open.compareTo(high) > 0
                        || close.compareTo(low) < 0 || close.compareTo(high) > 0
                        || buyVolume.compareTo(volume) > 0 || buyQuote.compareTo(quoteVolume) > 0) {
                    throw new IllegalArgumentException();
                }
                var candle = new Kline(openTime, closeTime, open, high, low, close, volume, quoteVolume, trades,
                        buyVolume, buyQuote, receivedAt);
                Kline previous = sorted.putIfAbsent(openTime, candle);
                if (previous != null && !previous.equals(candle)) throw new IllegalArgumentException();
            }
            if (sorted.isEmpty()) throw new KlineException("NO_CLOSED_DATA", "交易所未返回该区间的闭合 K 线", 30);
            return new ArrayList<>(sorted.values());
        } catch (KlineException exception) { throw exception; }
        catch (Exception exception) { throw invalid(); }
    }

    private String get(String path) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json").GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429 || response.statusCode() == 418) {
                long delay = response.headers().firstValue("Retry-After").map(this::retryDelay).orElse(60L);
                throw new KlineException("UPSTREAM_RATE_LIMIT", "交易所限流，采集已退避", delay);
            }
            if (response.statusCode() != 200) {
                throw new KlineException("UPSTREAM_UNAVAILABLE", "交易所返回 HTTP " + response.statusCode(),
                        response.statusCode() == 451 ? 300 : 30);
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KlineException("INTERRUPTED", "K 线采集已中断", 30);
        } catch (IOException exception) {
            throw new KlineException("NETWORK_UNAVAILABLE", "无法连接交易所，已有 K 线保留", 30);
        }
    }

    private long integer(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException();
        return value.longValue();
    }

    private BigDecimal decimal(JsonNode value, boolean positive) {
        if (value == null || !value.isTextual() || value.textValue().length() > 80) throw new IllegalArgumentException();
        BigDecimal number = new BigDecimal(value.textValue());
        if (number.signum() < 0 || (positive && number.signum() == 0) || number.stripTrailingZeros().scale() > 18
                || number.precision() - number.scale() > 20) throw new IllegalArgumentException();
        return number;
    }

    private long retryDelay(String value) {
        try { return Math.max(60, Long.parseLong(value)); }
        catch (NumberFormatException exception) {
            try { return Math.max(60, Duration.between(Instant.now(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds()); }
            catch (Exception ignored) { return 60; }
        }
    }

    private KlineException invalid() {
        return new KlineException("INVALID_UPSTREAM_DATA", "交易所 K 线格式或数值异常，本批未写入", 60);
    }
}
