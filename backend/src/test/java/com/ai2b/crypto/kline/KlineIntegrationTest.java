package com.ai2b.crypto.kline;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.ai2b.crypto.instrument.Instrument;
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
class KlineIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-27T12:00:05Z");
    private static final Instant END = KlineCollector.latestClosedOpen(NOW.minusSeconds(2));
    private static final Instant START = END.minusSeconds(59 * 60L);
    @Autowired KlineRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean Clock clock;
    @MockitoBean BinanceKlineClient client;
    private Instrument instrument;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(NOW);
        instrument = insertInstrument("BTCUSDT");
    }

    private Instrument insertInstrument(String symbol) {
        jdbc.update("""
                INSERT INTO instrument (exchange,market_type,symbol,base_asset,quote_asset,margin_asset,contract_type,
                status,price_tick_size,quantity_step_size,created_at,updated_at)
                VALUES ('BINANCE','USD_M_FUTURES',?,'BTC','USDT','USDT','PERPETUAL','TRADING',1,1,?,?)
                """, symbol, Timestamp.from(NOW), Timestamp.from(NOW));
        Long id = jdbc.queryForObject("SELECT id FROM instrument WHERE symbol=?", Long.class, symbol);
        return new Instrument(id, "BINANCE", "USD_M_FUTURES", symbol, "BTC", "USDT", "USDT", "PERPETUAL", "TRADING",
                BigDecimal.ONE, BigDecimal.ONE, NOW);
    }

    private Kline candle(Instant time) {
        return new Kline(time, time.plusMillis(59999), new BigDecimal("100.000000000000000001"), new BigDecimal("110"),
                new BigDecimal("90"), new BigDecimal("101"), BigDecimal.TEN, new BigDecimal("1000"), 4,
                BigDecimal.ONE, new BigDecimal("100"), NOW);
    }

    @Test
    void duplicateAndOutOfOrderRowsAreIdempotentAndGapStopsWatermark() {
        List<Kline> sparse = List.of(candle(START.plusSeconds(120)), candle(START));
        Instant watermark = repository.savePage(instrument, sparse, START, START, START.plusSeconds(120), NOW);
        assertThat(watermark).isEqualTo(START);
        repository.savePage(instrument, sparse, START, START, START.plusSeconds(120), NOW);
        assertThat(repository.list(instrument.id(), START, END)).hasSize(2);
        assertThat(repository.checkpoint("BTCUSDT").orElseThrow().contiguousOpen()).isEqualTo(START);
        watermark = repository.savePage(instrument, List.of(candle(START.plusSeconds(60))), START,
                START.plusSeconds(60), START.plusSeconds(120), NOW);
        assertThat(watermark).isEqualTo(START.plusSeconds(120));
        assertThat(repository.list(instrument.id(), START, END)).hasSize(3);
    }

    @Test
    void missingFirstMinuteKeepsNullCheckpointUntilItIsFilled() {
        repository.savePage(instrument, List.of(candle(START.plusSeconds(60))), START, START, START.plusSeconds(60), NOW);
        assertThat(repository.checkpoint("BTCUSDT").orElseThrow().contiguousOpen()).isNull();
        repository.savePage(instrument, List.of(candle(START)), START, START, START.plusSeconds(60), NOW);
        assertThat(repository.checkpoint("BTCUSDT").orElseThrow().contiguousOpen()).isEqualTo(START.plusSeconds(60));
    }

    @Test
    void exactPrecisionAndGapCountsAreExposedWithoutSyntheticCandles() throws Exception {
        repository.savePage(instrument, List.of(candle(START), candle(END)), START, START, END, NOW);
        mvc.perform(get("/api/v1/instruments/BTCUSDT/klines").param("hours", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expectedBars").value(60))
                .andExpect(jsonPath("$.missingBars").value(58)).andExpect(jsonPath("$.quality").value("GAPPED"))
                .andExpect(jsonPath("$.stale").value(false)).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].open").value("100.000000000000000001"))
                .andExpect(jsonPath("$.gaps[0].minutes").value(58)).andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void emptyAndStaleWindowsRemainExplicit() throws Exception {
        mvc.perform(get("/api/v1/instruments/BTCUSDT/klines").param("hours", "1"))
                .andExpect(jsonPath("$.quality").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.missingBars").value(60)).andExpect(jsonPath("$.stale").value(true));
        repository.savePage(instrument, List.of(candle(START)), START, START, START, NOW);
        mvc.perform(get("/api/v1/instruments/BTCUSDT/klines").param("hours", "1"))
                .andExpect(jsonPath("$.quality").value("GAPPED")).andExpect(jsonPath("$.stale").value(true));
    }

    @Test
    void validatesRangeScopeAndManualActionHeader() throws Exception {
        for (String hours : List.of("0", "25", "oops")) {
            mvc.perform(get("/api/v1/instruments/BTCUSDT/klines").param("hours", hours))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        mvc.perform(get("/api/v1/instruments/SOLUSDT/klines")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/instruments/ETHUSDT/klines")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/v1/instruments/BTCUSDT/klines/sync")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/instruments/SOLUSDT/klines/sync").header("X-AI2B-Action", "collect-klines"))
                .andExpect(status().isNotFound());
    }

    @Test
    void failedBatchRollsBackRowsAndCheckpointTogether() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Instrument committed = tx.execute(ignored -> insertInstrument("ROLLBACKUSDT"));
        try {
            Kline valid = candle(START);
            Kline invalid = new Kline(START.plusSeconds(60), START.plusSeconds(60).plusMillis(59999),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, NOW);
            assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> repository.savePage(committed,
                    List.of(valid, invalid), START, START, START.plusSeconds(60), NOW))).isInstanceOf(RuntimeException.class);
            tx.executeWithoutResult(ignored -> {
                assertThat(repository.list(committed.id(), START, END)).isEmpty();
                assertThat(repository.checkpoint("ROLLBACKUSDT")).isEmpty();
            });
        } finally {
            tx.executeWithoutResult(ignored -> jdbc.update("DELETE FROM instrument WHERE id=?", committed.id()));
        }
    }
}
