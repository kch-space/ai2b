package com.ai2b.crypto.instrument;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BinanceInstrumentClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI endpoint;
    private final ObjectMapper mapper;

    public BinanceInstrumentClient(ObjectMapper mapper,
            @Value("${market.binance.rest-base-url}") String baseUrl) {
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/fapi/v1/exchangeInfo");
    }

    public List<Instrument> fetch() {
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json").GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429 || response.statusCode() == 418) {
                long delay = response.headers().firstValue("Retry-After").map(this::retryDelay).orElse(60L);
                throw new MarketDataException("交易所限流，稍后再试", delay);
            }
            if (response.statusCode() != 200) {
                throw new MarketDataException("交易所返回 HTTP " + response.statusCode());
            }
            return parse(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MarketDataException("合约同步已中断");
        } catch (IOException exception) {
            throw new MarketDataException("无法连接交易所，请检查网络后重试");
        }
    }

    List<Instrument> parse(String body) {
        try {
            JsonNode symbols = mapper.readTree(body).get("symbols");
            if (symbols == null || !symbols.isArray() || symbols.isEmpty()) throw new IllegalArgumentException();
            List<Instrument> result = new ArrayList<>();
            var identities = new HashSet<String>();
            for (JsonNode item : symbols) {
                // Validate scope fields before filtering: malformed payloads must never retire existing records.
                String contractType = text(item, "contractType", 32);
                String quote = text(item, "quoteAsset", 32);
                String margin = text(item, "marginAsset", 32);
                if (!contractType.equals("PERPETUAL") || !quote.equals("USDT") || !margin.equals("USDT")) continue;
                String symbol = text(item, "symbol", 64);
                if (!identities.add(symbol)) throw new IllegalArgumentException();
                result.add(new Instrument(null, "BINANCE", "USD_M_FUTURES", symbol,
                        text(item, "baseAsset", 32), quote, margin, contractType, text(item, "status", 32),
                        filter(item, "PRICE_FILTER", "tickSize"), filter(item, "LOT_SIZE", "stepSize"), null));
            }
            if (result.isEmpty()) throw new IllegalArgumentException();
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new MarketDataException("交易所合约数据格式异常，本次未更新数据");
        }
    }

    private String text(JsonNode item, String field, int maxLength) {
        JsonNode value = item.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maxLength) throw new IllegalArgumentException();
        return value.asText();
    }

    private BigDecimal filter(JsonNode item, String type, String field) {
        JsonNode filters = item.get("filters");
        if (filters == null || !filters.isArray()) throw new IllegalArgumentException();
        for (JsonNode filter : filters) {
            if (type.equals(filter.path("filterType").asText())) {
                BigDecimal value = new BigDecimal(text(filter, field, 80));
                if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 18
                        || value.precision() - value.scale() > 20) throw new IllegalArgumentException();
                return value;
            }
        }
        throw new IllegalArgumentException();
    }

    private long retryDelay(String value) {
        try { return Math.max(60, Long.parseLong(value)); }
        catch (NumberFormatException exception) { return 60; }
    }
}
