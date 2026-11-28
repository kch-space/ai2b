package com.ai2b.crypto.kline;

import static org.assertj.core.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class BinanceKlineClientTest {
    static final Instant START = Instant.parse("2026-11-26T00:00:00Z");
    private final BinanceKlineClient client = new BinanceKlineClient(new ObjectMapper(), "https://example.invalid");

    static String row(Instant time, String close) {
        return "[" + time.toEpochMilli() + ",\"100.000000000000000001\",\"110\",\"90\",\"" + close
                + "\",\"10\"," + (time.toEpochMilli() + 59999) + ",\"1000\",12,\"4\",\"400\",\"0\"]";
    }

    @Test
    void sortsAndDeduplicatesClosedCandlesWithoutLosingDecimalPrecision() {
        var result = client.parse("[" + row(START.plusSeconds(60), "102") + "," + row(START, "101")
                + "," + row(START, "101") + "]", START, START.plusSeconds(60), START.plusSeconds(120), START.plusSeconds(121));
        assertThat(result).extracting(Kline::openTime).containsExactly(START, START.plusSeconds(60));
        assertThat(result.getFirst().open()).isEqualByComparingTo("100.000000000000000001");
        assertThat(result.getFirst().quoteVolume()).isEqualByComparingTo("1000");
        assertThat(result.getFirst().takerBuyVolume()).isEqualByComparingTo("4");
    }

    @Test
    void dropsUnclosedCandlesAndRejectsAnEntirelyUnclosedPage() {
        var result = client.parse("[" + row(START, "101") + "," + row(START.plusSeconds(60), "102") + "]",
                START, START.plusSeconds(60), START.plusSeconds(90), START.plusSeconds(90));
        assertThat(result).hasSize(1);
        assertThatThrownBy(() -> client.parse("[" + row(START, "101") + "]", START, START,
                START.plusSeconds(30), START.plusSeconds(30))).isInstanceOf(KlineException.class);
    }

    @Test
    void rejectsBadOhlcNegativeVolumeConflictingDuplicatesAndMisalignedTime() {
        String valid = row(START, "101");
        for (String body : List.of("{}", "[]", "not-json", "[[]]", "[" + row(START, "111") + "]",
                "[" + valid.replace("\"10\"", "\"-1\"") + "]",
                "[" + valid.replace("100.000000000000000001", "100.0000000000000000001") + "]",
                "[" + valid + "," + row(START, "102") + "]",
                "[" + row(START.plusMillis(1), "101") + "]")) {
            assertThatThrownBy(() -> client.parse(body, START, START.plusSeconds(60), START.plusSeconds(120),
                    START.plusSeconds(120))).isInstanceOf(KlineException.class);
        }
    }

    @Test
    void rejectsClosedRowsOutsideRequestedRange() {
        assertThatThrownBy(() -> client.parse("[" + row(START.minusSeconds(60), "101") + "]", START, START,
                START.plusSeconds(120), START.plusSeconds(120))).isInstanceOf(KlineException.class);
    }

    @Test
    void httpAdapterUsesExplicitRangeAndReadsServerClock() throws Exception {
        var query = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fapi/v1/time", exchange -> {
            byte[] body = ("{\"serverTime\":" + START.plusSeconds(120).toEpochMilli() + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/fapi/v1/klines", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = ("[" + row(START, "101") + "]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var http = new BinanceKlineClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort());
            assertThat(http.serverTime()).isEqualTo(START.plusSeconds(120));
            assertThat(http.fetch("BTCUSDT", START, START, START.plusSeconds(120))).hasSize(1);
            assertThat(query.get()).contains("symbol=BTCUSDT", "interval=1m", "startTime=" + START.toEpochMilli(),
                    "endTime=" + (START.toEpochMilli() + 59999), "limit=499");
        } finally { server.stop(0); }
    }

    @Test
    void rateLimitPreservesRetryAfterAndDoesNotExposeUpstreamBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fapi/v1/time", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "172800");
            byte[] body = "private upstream detail".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var http = new BinanceKlineClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort());
            assertThatThrownBy(http::serverTime).isInstanceOfSatisfying(KlineException.class, error -> {
                assertThat(error.retryAfterSeconds).isEqualTo(172800);
                assertThat(error.getMessage()).doesNotContain("private");
            });
        } finally { server.stop(0); }
    }
}
