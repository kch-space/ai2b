package com.ai2b.crypto.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class InstrumentSyncServiceTest {
    private final BinanceInstrumentClient client = mock(BinanceInstrumentClient.class);
    private final InstrumentRepository repository = mock(InstrumentRepository.class);
    private final List<Instrument> snapshot = List.of(new Instrument(null, "BINANCE", "USD_M_FUTURES", "BTCUSDT",
            "BTC", "USDT", "USDT", "PERPETUAL", "TRADING", new BigDecimal("0.1"), new BigDecimal("0.001"), null));

    @Test
    void concurrentRequestsCannotStartTwoFetches() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.fetch()).thenAnswer(ignored -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fetch was not released");
            return snapshot;
        });
        var service = new InstrumentSyncService(client, repository, true, 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(service::sync);
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(service.status().running()).isTrue();
                assertThatThrownBy(service::sync).isInstanceOf(InstrumentSyncService.SyncBusyException.class);
                verify(client, times(1)).fetch();
            } finally { release.countDown(); }
            assertThat(first.get(2, TimeUnit.SECONDS).outcome()).isEqualTo("SUCCESS");
        }
        assertThat(service.status().running()).isFalse();
    }

    @Test
    void successfulSyncHasCooldownAndDisabledScheduleDoesNotFetch() {
        var service = new InstrumentSyncService(client, repository, false, 60);
        service.scheduledSync();
        verifyNoInteractions(client);
        when(client.fetch()).thenReturn(snapshot);
        assertThat(service.sync().outcome()).isEqualTo("SUCCESS");
        assertThatThrownBy(service::sync).isInstanceOf(InstrumentSyncService.SyncBusyException.class);
        verify(client, times(1)).fetch();
    }
}
