package com.ai2b.crypto.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class BinanceInstrumentClientTest {
    private final BinanceInstrumentClient client = new BinanceInstrumentClient(new ObjectMapper(), "https://example.invalid");

    static String item(String symbol, String contractType, String quote, String status, String tick) {
        return """
                {"symbol":"%s","contractType":"%s","quoteAsset":"%s","marginAsset":"%s",
                 "baseAsset":"BTC","status":"%s","pricePrecision":2,"quantityPrecision":3,
                 "filters":[{"filterType":"PRICE_FILTER","tickSize":"%s"},
                            {"filterType":"LOT_SIZE","stepSize":"0.00100000"}]}
                """.formatted(symbol, contractType, quote, quote, status, tick);
    }

    static String snapshot(String... items) { return "{\"symbols\":[" + String.join(",", items) + "]}"; }

    @Test
    void filtersScopeAndUsesTradingFiltersRatherThanPrecisionDigits() {
        var items = client.parse(snapshot(item("BTCUSDT", "PERPETUAL", "USDT", "TRADING", "0.01000000"),
                item("ETHUSDT", "PERPETUAL", "USDT", "SETTLING", "0.000000000000000001"),
                item("BTCUSDC", "PERPETUAL", "USDC", "TRADING", "0.01"),
                item("BTC_QUARTER", "CURRENT_QUARTER", "USDT", "TRADING", "0.01")));
        assertThat(items).extracting(Instrument::symbol).containsExactly("BTCUSDT", "ETHUSDT");
        assertThat(items.getFirst().priceTickSize()).isEqualByComparingTo("0.01");
        assertThat(items.get(1).priceTickSize()).isEqualByComparingTo("0.000000000000000001");
        assertThat(items.get(1).status()).isEqualTo("SETTLING");
    }

    @Test
    void rejectsIncompleteEmptyDuplicateAndInvalidPrecisionSnapshots() {
        String valid = item("BTCUSDT", "PERPETUAL", "USDT", "TRADING", "0.01");
        for (String body : List.of("{}", "{\"symbols\":[]}", "not-json", snapshot(valid, valid),
                snapshot(valid.replace("PRICE_FILTER", "OTHER_FILTER")),
                snapshot(valid.replace("0.01", "0")), snapshot(valid.replace("0.01", "0.0000000000000000001")),
                snapshot(valid, "{\"symbol\":\"BROKEN\"}"))) {
            assertThatThrownBy(() -> client.parse(body)).isInstanceOf(MarketDataException.class);
        }
    }

    @Test
    void httpAdapterUsesDocumentedPathAndHonorsRateLimitDelay() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fapi/v1/exchangeInfo", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "180");
            byte[] body = "do not expose upstream response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var httpClient = new BinanceInstrumentClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort());
            assertThatThrownBy(httpClient::fetch).isInstanceOfSatisfying(MarketDataException.class, failure -> {
                assertThat(failure.retryAfterSeconds()).isEqualTo(180);
                assertThat(failure.getMessage()).contains("限流").doesNotContain("upstream");
            });
        } finally { server.stop(0); }
    }

    @Test
    void fetchesAndParsesAnHttpSnapshot() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fapi/v1/exchangeInfo", exchange -> {
            byte[] body = snapshot(item("BTCUSDT", "PERPETUAL", "USDT", "TRADING", "0.1"))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var httpClient = new BinanceInstrumentClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort());
            assertThat(httpClient.fetch()).extracting(Instrument::symbol).containsExactly("BTCUSDT");
        } finally { server.stop(0); }
    }
}
