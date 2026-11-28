package com.ai2b.crypto.metrics;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

import com.ai2b.crypto.kline.KlineCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class BinanceMetricClient {
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;

    @Autowired
    public BinanceMetricClient(ObjectMapper mapper, @Value("${market.binance.rest-base-url}") String baseUrl,
            @Value("${market.binance.api-key:}") String apiKey) {
        this(mapper, baseUrl, apiKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    BinanceMetricClient(ObjectMapper mapper, String baseUrl, String apiKey, HttpClient http) {
        this.mapper = mapper; this.baseUrl = baseUrl.replaceAll("/+$", ""); this.apiKey = apiKey; this.http = http;
    }
    public boolean keyConfigured() { return !apiKey.isBlank(); }

    public List<MetricPoint> fetch(String symbol, MetricType type, Instant start, Instant end, Instant now) {
        KlineCollector.requireSupported(symbol);
        String query = "?symbol=" + symbol;
        if (type != MetricType.FUNDING_SNAPSHOT) {
            query += "&startTime=" + start.toEpochMilli() + "&endTime=" + end.toEpochMilli() + "&limit=500";
            if (type.fiveMinute) query += "&period=5m";
        }
        return parse(get(type, query), symbol, type, start, end, now);
    }

    private String get(MetricType type, String query) {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + type.path + query))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET();
        if (type.requiresKey) {
            if (!keyConfigured()) throw new MetricException("API_KEY_REQUIRED", "该行情接口需要配置币安 API Key", 300, false);
            // Credentials must never follow a configurable URL onto a different host or a redirect.
            if (!baseUrl.equals("https://fapi.binance.com")) {
                throw new MetricException("UNTRUSTED_SOURCE", "鉴权行情仅允许发送到币安官方地址", 300, false);
            }
            builder.header("X-MBX-APIKEY", apiKey);
        }
        try {
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 429 || status == 418) {
                long delay = response.headers().firstValue("Retry-After").map(this::retryDelay).orElse(60L);
                throw new MetricException("RATE_LIMIT", "币安行情限流，已暂停请求", delay, true);
            }
            int errorCode = 0;
            if (status == 400) {
                try { errorCode = mapper.readTree(response.body()).path("code").asInt(); }
                catch (Exception ignored) { /* Never expose upstream response bodies. */ }
            }
            if (status == 401 || status == 403 || errorCode == -2015 || errorCode == -2014 || errorCode == -1022) {
                throw new MetricException("ACCESS_DENIED", "行情接口拒绝访问（HTTP " + status + "），请检查密钥、权限与 IP 限制", 900, false);
            }
            if (status != 200) throw new MetricException("UPSTREAM_UNAVAILABLE", "币安行情返回 HTTP " + status,
                    status == 451 ? 300 : 60, true);
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MetricException("INTERRUPTED", "行情采集已中断", 60, false);
        } catch (IOException exception) {
            throw new MetricException("NETWORK_UNAVAILABLE", "无法连接币安，已有指标保留", 60, true);
        }
    }

    List<MetricPoint> parse(String body, String symbol, MetricType type, Instant start, Instant end, Instant now) {
        try {
            JsonNode root = mapper.readTree(body);
            boolean snapshot = type == MetricType.FUNDING_SNAPSHOT;
            if (root == null || (snapshot ? !root.isObject() : !root.isArray() || root.size() > 500)) throw new IllegalArgumentException();
            Iterable<JsonNode> rows = snapshot ? List.of(root) : root;
            var points = new TreeMap<Instant, MetricPoint>();
            for (JsonNode row : rows) {
                if (!row.isObject() || !symbol.equals(row.path("symbol").asText())) throw new IllegalArgumentException();
                String timeField = snapshot ? "time" : type == MetricType.FUNDING_HISTORY ? "fundingTime" : "timestamp";
                Instant eventTime = timestamp(row.get(timeField));
                if (eventTime.isAfter(now.plusSeconds(60)) || (!snapshot && (eventTime.isBefore(start) || eventTime.isAfter(end)))
                        || (type.fiveMinute && eventTime.toEpochMilli() % 300000 != 0)) throw new IllegalArgumentException();
                var values = new LinkedHashMap<String, String>();
                switch (type) {
                    case OPEN_INTEREST -> {
                        values.put("quantity", decimal(row, "sumOpenInterest", false, false));
                        values.put("notional", decimal(row, "sumOpenInterestValue", false, false));
                    }
                    case FUNDING_SNAPSHOT -> {
                        values.put("markPrice", decimal(row, "markPrice", true, false));
                        values.put("indexPrice", decimal(row, "indexPrice", true, false));
                        values.put("rate", decimal(row, "lastFundingRate", false, true));
                        values.put("nextFundingTime", timestamp(row.get("nextFundingTime")).toString());
                    }
                    case FUNDING_HISTORY -> {
                        values.put("rate", decimal(row, "fundingRate", false, true));
                        values.put("markPrice", decimal(row, "markPrice", true, false));
                    }
                    default -> {
                        values.put("longShare", decimal(row, "longAccount", false, false));
                        values.put("shortShare", decimal(row, "shortAccount", false, false));
                        values.put("ratio", decimal(row, "longShortRatio", false, false));
                        BigDecimal longShare = new BigDecimal(values.get("longShare")), shortShare = new BigDecimal(values.get("shortShare"));
                        if (longShare.compareTo(BigDecimal.ONE) > 0 || shortShare.compareTo(BigDecimal.ONE) > 0
                                || longShare.add(shortShare).subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.001")) > 0) {
                            throw new IllegalArgumentException();
                        }
                    }
                }
                var point = new MetricPoint(eventTime, now, values);
                MetricPoint previous = points.putIfAbsent(eventTime, point);
                if (previous != null && !previous.equals(point)) throw new IllegalArgumentException();
            }
            if (points.isEmpty() && type != MetricType.FUNDING_HISTORY) {
                throw new MetricException("NO_DATA", "币安尚未返回该指标数据，已有记录保留", 300, false);
            }
            return List.copyOf(points.values());
        } catch (MetricException exception) { throw exception; }
        catch (Exception exception) { throw new MetricException("INVALID_DATA", "币安指标格式或数值异常，本批未保存", 300, false); }
    }

    private Instant timestamp(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) throw new IllegalArgumentException();
        return Instant.ofEpochMilli(value.longValue());
    }
    private String decimal(JsonNode row, String field, boolean positive, boolean signed) {
        JsonNode value = row.get(field);
        if (value == null || !value.isTextual() || value.textValue().length() > 80) throw new IllegalArgumentException();
        BigDecimal number = new BigDecimal(value.textValue());
        if ((!signed && number.signum() < 0) || (positive && number.signum() <= 0)
                || number.stripTrailingZeros().scale() > 18 || number.precision() - number.scale() > 20) throw new IllegalArgumentException();
        return number.stripTrailingZeros().toPlainString();
    }
    private long retryDelay(String value) {
        try { return Math.max(60, Long.parseLong(value)); }
        catch (NumberFormatException exception) {
            try { return Math.max(60, Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds()); }
            catch (Exception ignored) { return 60; }
        }
    }
}
