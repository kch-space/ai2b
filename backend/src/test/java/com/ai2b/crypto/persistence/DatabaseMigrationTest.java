package com.ai2b.crypto.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DatabaseMigrationTest {
    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void migrationIsAppliedAndRepeatRunIsNoOp() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM instrument", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM collector_checkpoint", Long.class)).isZero();
    }

    @Test
    void instrumentIdentityIsUniqueAndPrecisionIsPreserved() {
        BigDecimal tick = new BigDecimal("0.000000000000000001");
        insertInstrument("TEST_ONLY", tick);
        assertThat(jdbc.queryForObject("SELECT price_tick_size FROM instrument WHERE symbol = 'TEST_ONLY'",
                BigDecimal.class)).isEqualByComparingTo(tick);
        assertThatThrownBy(() -> insertInstrument("TEST_ONLY", tick))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void instrumentRejectsNonPositiveTickSize() {
        assertThatThrownBy(() -> insertInstrument("INVALID_TEST", BigDecimal.ZERO))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkpointIdentityIsUniqueAndCursorCanBeEmpty() {
        String sql = """
                INSERT INTO collector_checkpoint (source, stream_key, created_at, updated_at)
                VALUES ('TEST_ONLY', 'TEST_ONLY:1m', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """;
        jdbc.update(sql);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM collector_checkpoint", Long.class)).isOne();
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertInstrument(String symbol, BigDecimal tick) {
        jdbc.update("""
                INSERT INTO instrument (exchange, market_type, symbol, base_asset, quote_asset,
                    margin_asset, contract_type, status, price_tick_size, quantity_step_size,
                    created_at, updated_at)
                VALUES ('TEST_ONLY', 'FUTURES', ?, 'TEST', 'USDT', 'USDT', 'PERPETUAL', 'TRADING',
                    ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, symbol, tick);
    }
}
