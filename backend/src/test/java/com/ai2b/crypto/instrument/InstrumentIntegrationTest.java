package com.ai2b.crypto.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
class InstrumentIntegrationTest {
    @Autowired InstrumentRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean BinanceInstrumentClient client;

    private Instrument item(String symbol, String status, String tick) {
        return new Instrument(null, "BINANCE", "USD_M_FUTURES", symbol, "BTC", "USDT", "USDT", "PERPETUAL",
                status, new BigDecimal(tick), new BigDecimal("0.001"), null);
    }

    @Test
    void snapshotIsIdempotentUpdatesStatusAndPreservesMissingSymbolsAndCheckpoint() {
        Instant first = Instant.parse("2026-11-26T00:00:00Z");
        Instant second = first.plusSeconds(3600);
        repository.saveSnapshot(List.of(item("BTCUSDT", "TRADING", "0.1"), item("ETHUSDT", "TRADING", "0.01")), first);
        Long id = jdbc.queryForObject("SELECT id FROM instrument WHERE symbol='BTCUSDT'", Long.class);
        repository.saveSnapshot(List.of(item("BTCUSDT", "SETTLING", "0.000000000000000001")), second);
        repository.saveSnapshot(List.of(item("BTCUSDT", "SETTLING", "0.000000000000000001")), second);
        var result = repository.list("", "", 1, 50);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items().getFirst().id()).isEqualTo(id);
        assertThat(result.items().getFirst().priceTickSize()).isEqualByComparingTo("0.000000000000000001");
        assertThat(result.items()).extracting(Instrument::status).containsExactly("SETTLING", "NOT_IN_SNAPSHOT");
        assertThat(repository.lastSuccessAt()).isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT created_at FROM instrument WHERE symbol='BTCUSDT'", java.sql.Timestamp.class)
                .toInstant()).isEqualTo(first);
        repository.saveSnapshot(List.of(item("BTCUSDT", "TRADING", "0.1"), item("ETHUSDT", "TRADING", "0.01")), second);
        assertThat(repository.list("", "TRADING", 1, 50).total()).isEqualTo(2);
    }

    @Test
    void failedWriteRollsBackWholeSnapshotAndDoesNotAdvanceCheckpoint() {
        Instant first = Instant.parse("2026-11-26T00:00:00Z");
        // Use an independent committed transaction so rollback semantics are observable outside the failed transaction.
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(ignored -> repository.saveSnapshot(List.of(item("ROLLBACKUSDT", "TRADING", "0.1")), first));
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> repository.saveSnapshot(List.of(
                    item("ROLLBACKUSDT", "SETTLING", "0.2"), item("INVALIDUSDT", "TRADING", "0")), first.plusSeconds(60))))
                    .isInstanceOf(RuntimeException.class);
            tx.executeWithoutResult(ignored -> {
                assertThat(repository.list("ROLLBACK", "TRADING", 1, 50).total()).isEqualTo(1);
                assertThat(repository.lastSuccessAt()).isEqualTo(first);
            });
        } finally {
            tx.executeWithoutResult(ignored -> {
                jdbc.update("DELETE FROM instrument WHERE symbol='ROLLBACKUSDT'");
                jdbc.update("DELETE FROM collector_checkpoint WHERE source='BINANCE'");
            });
        }
    }

    @Test
    void listIsPaginatedFilteredAndSerializesDecimalsAsStrings() throws Exception {
        repository.saveSnapshot(List.of(item("BTCUSDT", "TRADING", "0.000000000000000001"),
                item("ETHUSDT", "SETTLING", "0.01")), Instant.now());
        mvc.perform(get("/api/v1/instruments").param("keyword", "btc").param("status", "TRADING").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].symbol").value("BTCUSDT"))
                .andExpect(jsonPath("$.items[0].priceTickSize", is("0.000000000000000001")))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        mvc.perform(get("/api/v1/instruments").param("size", "1").param("page", "2"))
                .andExpect(jsonPath("$.items[0].symbol").value("ETHUSDT"));
        mvc.perform(get("/api/v1/instruments").param("keyword", "_"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void validatesPaginationAndProtectsSyncAgainstCrossSiteFormPosts() throws Exception {
        for (String size : List.of("0", "201", "bad")) mvc.perform(get("/api/v1/instruments").param("size", size))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mvc.perform(get("/api/v1/instruments").param("page", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/instruments").param("keyword", "' OR 1=1")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/instruments/sync")).andExpect(status().isBadRequest());
    }

    @Test
    void manualSyncPersistsTheSnapshot() throws Exception {
        when(client.fetch()).thenReturn(List.of(item("BTCUSDT", "TRADING", "0.1")));
        mvc.perform(post("/api/v1/instruments/sync").header("X-AI2B-Action", "sync-instruments"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sync.outcome").value("SUCCESS"));
        assertThat(repository.list("BTC", "", 1, 50).total()).isEqualTo(1);
    }

    @Test
    void upstreamFailureLeavesPreviousSnapshotAndExposesRetryAfter() {
        repository.saveSnapshot(List.of(item("BTCUSDT", "TRADING", "0.1")), Instant.now());
        Instant checkpoint = repository.lastSuccessAt();
        when(client.fetch()).thenThrow(new MarketDataException("交易所返回 HTTP 451", 120));
        var service = new InstrumentSyncService(client, repository, false, 0);
        assertThatThrownBy(service::sync).isInstanceOf(MarketDataException.class);
        assertThat(repository.lastSuccessAt()).isEqualTo(checkpoint);
        assertThat(repository.list("", "", 1, 50).total()).isEqualTo(1);
        assertThat(service.status().error()).contains("451");
        assertThatThrownBy(service::sync).isInstanceOf(InstrumentSyncService.SyncBusyException.class);
    }
}
