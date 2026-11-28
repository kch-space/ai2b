package com.ai2b.crypto.metrics;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MetricIntegrationTest {
    static final Instant NOW = Instant.parse("2026-11-27T12:00:05Z"), END = NOW.minusSeconds(5), START = END.minusSeconds(287 * 300L);
    @Autowired MetricRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean Clock clock;
    @MockitoBean BinanceMetricClient client;
    long id;
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.update("""
                INSERT INTO instrument (exchange,market_type,symbol,base_asset,quote_asset,margin_asset,contract_type,status,
                price_tick_size,quantity_step_size,created_at,updated_at)
                VALUES ('BINANCE','USD_M_FUTURES','BTCUSDT','BTC','USDT','USDT','PERPETUAL','TRADING',0.1,0.001,?,?)
                """, Timestamp.from(NOW), Timestamp.from(NOW));
        id = jdbc.queryForObject("SELECT id FROM instrument WHERE symbol='BTCUSDT'", Long.class);
    }
    MetricPoint point(Instant time) { return new MetricPoint(time, NOW, Map.of("quantity", "100.000000000000000001", "notional", "6000000")); }

    @Test void storesPreciseHistoryIdempotentlyAndPersistsFailureWithoutLosingData() {
        repository.save(id, MetricType.OPEN_INTEREST, List.of(point(END), point(START)), NOW, NOW.plusSeconds(300));
        repository.save(id, MetricType.OPEN_INTEREST, List.of(point(START), point(END), point(END)), NOW, NOW.plusSeconds(300));
        assertThat(repository.list(id, MetricType.OPEN_INTEREST, START, END)).hasSize(2);
        assertThat(repository.list(id, MetricType.OPEN_INTEREST, START, END).getFirst().values()).containsEntry("quantity", "100.000000000000000001");
        repository.fail(id, MetricType.OPEN_INTEREST, NOW.plusSeconds(10), NOW.plusSeconds(600), 1,
                new MetricException("NETWORK_UNAVAILABLE", "network unavailable", 600, true));
        assertThat(repository.sync(id, MetricType.OPEN_INTEREST).lastSuccessAt()).isEqualTo(NOW);
        assertThat(repository.sourceGate().until()).isEqualTo(NOW.plusSeconds(600));
        assertThat(repository.list(id, MetricType.OPEN_INTEREST, START, END)).hasSize(2);
    }
    @Test void apiReportsIndependentGapCountsAndNoCredentials() throws Exception {
        repository.save(id, MetricType.OPEN_INTEREST, List.of(point(START), point(END)), NOW, NOW.plusSeconds(300));
        String json = mvc.perform(get("/api/v1/instruments/BTCUSDT/metrics")).andExpect(status().isOk())
                .andExpect(jsonPath("$.streams.length()").value(6)).andExpect(jsonPath("$.streams[0].missingPoints").value(286))
                .andExpect(jsonPath("$.streams[0].quality").value("GAPPED"))
                .andExpect(jsonPath("$.streams[0].latest.values.quantity").value("100.000000000000000001"))
                .andExpect(jsonPath("$.streams[1].quality").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.streams[4].authentication").value("API_KEY"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("apiSecret", "api-key", "BINANCE_API_KEY", "X-MBX");
    }
    @Test void completeWindowAndStaleObservationAreDistinct() throws Exception {
        var points = java.util.stream.IntStream.range(0, 288).mapToObj(i -> point(START.plusSeconds(i * 300L))).toList();
        repository.save(id, MetricType.OPEN_INTEREST, points, NOW, NOW.plusSeconds(300));
        repository.save(id, MetricType.FUNDING_SNAPSHOT, List.of(new MetricPoint(NOW.minusSeconds(1000), NOW,
                Map.of("rate", "-0.0001", "markPrice", "60000", "indexPrice", "60001", "nextFundingTime", NOW.plusSeconds(3600).toString()))), NOW, NOW.plusSeconds(300));
        mvc.perform(get("/api/v1/instruments/BTCUSDT/metrics")).andExpect(jsonPath("$.streams[0].quality").value("READY"))
                .andExpect(jsonPath("$.streams[0].missingPoints").value(0)).andExpect(jsonPath("$.streams[1].quality").value("STALE"))
                .andExpect(jsonPath("$.streams[1].latest.values.rate").value("-0.0001"));
    }
    @Test void validatesScopeAndManualRequestHeaderAndHonorsPersistedCooldown() throws Exception {
        mvc.perform(get("/api/v1/instruments/SOLUSDT/metrics")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/instruments/ETHUSDT/metrics")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/v1/instruments/BTCUSDT/metrics/sync")).andExpect(status().isBadRequest());
        for (MetricType type : MetricType.values()) repository.save(id, type, List.of(), NOW, NOW.plusSeconds(300));
        mvc.perform(post("/api/v1/instruments/BTCUSDT/metrics/sync").header("X-AI2B-Action", "collect-metrics"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
    @Test void failedWriteRollsBackDataAndSuccessState() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> {
            repository.save(id, MetricType.OPEN_INTEREST, List.of(point(END)), NOW, NOW.plusSeconds(300));
            throw new IllegalStateException("test transaction abort");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.list(id, MetricType.OPEN_INTEREST, START, END)).isEmpty();
        assertThat(repository.sync(id, MetricType.OPEN_INTEREST).lastSuccessAt()).isNull();
    }
}
