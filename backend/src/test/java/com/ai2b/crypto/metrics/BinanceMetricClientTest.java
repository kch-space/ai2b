package com.ai2b.crypto.metrics;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BinanceMetricClientTest {
    static final Instant NOW = Instant.parse("2026-11-27T12:00:05Z"), TIME = NOW.minusSeconds(5);
    final ObjectMapper mapper = new ObjectMapper();
    final BinanceMetricClient parser = new BinanceMetricClient(mapper, "https://fapi.binance.com", "");

    static String ratio(Instant time) {
        return "{\"symbol\":\"BTCUSDT\",\"timestamp\":" + time.toEpochMilli()
                + ",\"longAccount\":\"0.6000\",\"shortAccount\":\"0.4000\",\"longShortRatio\":\"1.5000\"}";
    }
    static String oi(Instant time) {
        return "{\"symbol\":\"BTCUSDT\",\"timestamp\":" + time.toEpochMilli()
                + ",\"sumOpenInterest\":\"100.000000000000000001\",\"sumOpenInterestValue\":\"6000000\"}";
    }
    List<MetricPoint> parse(String body, MetricType type) { return parser.parse(body, "BTCUSDT", type, TIME.minusSeconds(300), TIME, NOW); }

    @Test void preservesExactQuantitiesAndDeduplicatesSortedHistory() {
        var points = parse("[" + oi(TIME) + "," + oi(TIME.minusSeconds(300)) + "," + oi(TIME) + "]", MetricType.OPEN_INTEREST);
        assertThat(points).hasSize(2);
        assertThat(points.getFirst().eventTime()).isEqualTo(TIME.minusSeconds(300));
        assertThat(points.getFirst().values()).containsEntry("quantity", "100.000000000000000001").containsEntry("notional", "6000000");
    }
    @Test void separatesFundingSnapshotFromSettledRateAndPreservesNegativeRate() {
        String body = "{\"symbol\":\"BTCUSDT\",\"time\":" + NOW.toEpochMilli() + ",\"markPrice\":\"60000\",\"indexPrice\":\"60001\",\"lastFundingRate\":\"-0.00001234\",\"nextFundingTime\":" + NOW.plusSeconds(3600).toEpochMilli() + "}";
        var snapshot = parse(body, MetricType.FUNDING_SNAPSHOT).getFirst();
        assertThat(snapshot.values()).containsEntry("rate", "-0.00001234").containsEntry("nextFundingTime", NOW.plusSeconds(3600).toString());
        var history = parse("[{\"symbol\":\"BTCUSDT\",\"fundingTime\":" + TIME.toEpochMilli() + ",\"fundingRate\":\"0.0001\",\"markPrice\":\"60000\"}]", MetricType.FUNDING_HISTORY);
        assertThat(history.getFirst().values()).doesNotContainKey("nextFundingTime");
        assertThat(parse("[]", MetricType.FUNDING_HISTORY)).isEmpty();
    }
    @Test void rejectsOutOfRangeForeignSymbolBadSharesAndConflictingRows() {
        for (String body : List.of("[]", "{}", "[" + ratio(TIME).replace("BTCUSDT", "ETHUSDT") + "]",
                "[" + ratio(TIME.plusSeconds(300)) + "]", "[" + ratio(TIME.plusMillis(1)) + "]",
                "[" + ratio(TIME).replace("0.6000", "1.6000") + "]",
                "[" + ratio(TIME) + "," + ratio(TIME).replace("1.5000", "1.5001") + "]")) {
            assertThatThrownBy(() -> parse(body, MetricType.TOP_ACCOUNT_RATIO)).isInstanceOf(MetricException.class);
        }
        assertThatThrownBy(() -> parse("[" + oi(TIME).replace("100.000000000000000001", "100.0000000000000000001") + "]", MetricType.OPEN_INTEREST)).isInstanceOf(MetricException.class);
    }
    @SuppressWarnings("unchecked")
    HttpClient transport(int status, String body, Map<String, List<String>> headers) throws Exception {
        var http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status); when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (k, v) -> true));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        return http;
    }
    @Test void attachesApiKeyOnlyToRequiredOfficialEndpointsWithExplicitWindow() throws Exception {
        var http = transport(200, "[" + ratio(TIME) + "]", Map.of());
        var client = new BinanceMetricClient(mapper, "https://fapi.binance.com", "TEST_ONLY_KEY", http);
        client.fetch("BTCUSDT", MetricType.TOP_POSITION_RATIO, TIME.minusSeconds(300), TIME, NOW);
        client.fetch("BTCUSDT", MetricType.GLOBAL_ACCOUNT_RATIO, TIME.minusSeconds(300), TIME, NOW);
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(requests.capture(), any());
        assertThat(requests.getAllValues().getFirst().headers().firstValue("X-MBX-APIKEY")).contains("TEST_ONLY_KEY");
        assertThat(requests.getAllValues().getLast().headers().firstValue("X-MBX-APIKEY")).isEmpty();
        assertThat(requests.getValue().uri().getQuery()).contains("period=5m", "limit=500", "startTime=", "endTime=");
    }
    @Test void refusesMissingKeyAndNeverSendsCredentialsToCustomSource() throws Exception {
        var http = transport(200, "[]", Map.of());
        var missing = new BinanceMetricClient(mapper, "https://fapi.binance.com", "", http);
        var untrusted = new BinanceMetricClient(mapper, "https://example.invalid", "TEST_ONLY_KEY", http);
        assertThatThrownBy(() -> missing.fetch("BTCUSDT", MetricType.TOP_ACCOUNT_RATIO, TIME, TIME, NOW))
                .isInstanceOfSatisfying(MetricException.class, e -> assertThat(e.code).isEqualTo("API_KEY_REQUIRED"));
        assertThatThrownBy(() -> untrusted.fetch("BTCUSDT", MetricType.TOP_ACCOUNT_RATIO, TIME, TIME, NOW))
                .isInstanceOfSatisfying(MetricException.class, e -> assertThat(e.code).isEqualTo("UNTRUSTED_SOURCE"));
        verifyNoInteractions(http);
    }
    @Test void honorsLongRateLimitAndDoesNotLeakBodyOrKey() throws Exception {
        var http = transport(429, "TEST_ONLY_KEY private details", Map.of("Retry-After", List.of("172800")));
        var client = new BinanceMetricClient(mapper, "https://fapi.binance.com", "TEST_ONLY_KEY", http);
        assertThatThrownBy(() -> client.fetch("BTCUSDT", MetricType.TOP_ACCOUNT_RATIO, TIME, TIME, NOW))
                .isInstanceOfSatisfying(MetricException.class, e -> {
                    assertThat(e.retrySeconds).isEqualTo(172800); assertThat(e.sourceWide).isTrue();
                    assertThat(e.getMessage()).doesNotContain("TEST_ONLY_KEY", "private");
                });
    }
    @Test void authenticationFailureIsIsolatedFromPublicMetrics() throws Exception {
        var http = transport(400, "{\"code\":-2015,\"msg\":\"private upstream detail\"}", Map.of());
        var client = new BinanceMetricClient(mapper, "https://fapi.binance.com", "TEST_ONLY_KEY", http);
        assertThatThrownBy(() -> client.fetch("BTCUSDT", MetricType.TOP_ACCOUNT_RATIO, TIME, TIME, NOW))
                .isInstanceOfSatisfying(MetricException.class, e -> {
                    assertThat(e.code).isEqualTo("ACCESS_DENIED"); assertThat(e.sourceWide).isFalse();
                    assertThat(e.getMessage()).doesNotContain("private");
                });
    }
}
