package com.ai2b.crypto.metrics;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.ai2b.crypto.instrument.Instrument;
import com.ai2b.crypto.kline.KlineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetricCollectorTest {
    static final Instant NOW = Instant.parse("2026-11-27T12:00:05Z");
    final BinanceMetricClient client = mock(BinanceMetricClient.class);
    final MetricRepository repository = mock(MetricRepository.class);
    final KlineRepository instruments = mock(KlineRepository.class);
    final ArrayList<Runnable> jobs = new ArrayList<>();
    final HashMap<String, MetricRepository.Sync> sync = new HashMap<>();
    final AtomicReference<MetricRepository.Gate> gate = new AtomicReference<>(new MetricRepository.Gate(Instant.EPOCH, null));
    final MutableClock clock = new MutableClock();
    MetricCollector collector;
    static class MutableClock extends Clock {
        Instant now = NOW;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @BeforeEach void setup() {
        for (String symbol : List.of("BTCUSDT", "ETHUSDT")) {
            long id = symbol.equals("BTCUSDT") ? 1 : 2;
            when(instruments.findInstrument(symbol)).thenReturn(Optional.of(new Instrument(id,"BINANCE","USD_M_FUTURES",symbol,
                    symbol.substring(0,3),"USDT","USDT","PERPETUAL","TRADING",BigDecimal.ONE,BigDecimal.ONE,NOW)));
        }
        when(repository.sync(anyLong(), any())).thenAnswer(a -> sync.getOrDefault(a.getArgument(0) + ":" + a.getArgument(1),
                new MetricRepository.Sync(null,null,Instant.EPOCH,0,null,null)));
        when(repository.sourceGate()).thenAnswer(a -> gate.get());
        when(client.fetch(anyString(), any(), any(), any(), any())).thenReturn(List.of());
        doAnswer(a -> {
            sync.put(a.getArgument(0) + ":" + a.getArgument(1), new MetricRepository.Sync(a.getArgument(3),a.getArgument(3),a.getArgument(4),0,null,null));
            return null;
        }).when(repository).save(anyLong(),any(),anyList(),any(),any());
        doAnswer(a -> {
            MetricException error = a.getArgument(5);
            sync.put(a.getArgument(0) + ":" + a.getArgument(1), new MetricRepository.Sync(a.getArgument(2),null,a.getArgument(3),a.getArgument(4),error.code,error.getMessage()));
            if (error.sourceWide) gate.set(new MetricRepository.Gate(a.getArgument(3),error.getMessage()));
            return null;
        }).when(repository).fail(anyLong(),any(),any(),any(),anyInt(),any());
        collector = new MetricCollector(client,repository,instruments,jobs::add,clock,true);
    }
    @Test void singleFlightIsPerSymbolAndDispatchDoesNotBlockHttpCaller() {
        collector.trigger("BTCUSDT"); collector.trigger("ETHUSDT");
        assertThat(jobs).hasSize(2); verifyNoInteractions(client);
        assertThatThrownBy(() -> collector.trigger("BTCUSDT")).isInstanceOf(MetricException.class);
        jobs.removeFirst().run(); assertThat(collector.running("BTCUSDT")).isFalse();
        assertThat(collector.running("ETHUSDT")).isTrue();
    }
    @Test void requestsBoundedTwentyFourHourHistoryAndPersistsCooldown() {
        collector.trigger("BTCUSDT"); jobs.removeFirst().run();
        verify(client).fetch("BTCUSDT",MetricType.OPEN_INTEREST,Instant.parse("2026-11-26T12:05:00Z"),NOW.minusSeconds(5),NOW);
        verify(client).fetch("BTCUSDT",MetricType.FUNDING_HISTORY,NOW.minusSeconds(86400),NOW,NOW);
        assertThatThrownBy(() -> collector.trigger("BTCUSDT")).isInstanceOf(MetricException.class);
        clock.now = NOW.plusSeconds(331);
        // A fresh collector reads retry/success times from the same repository after restart.
        collector = new MetricCollector(client,repository,instruments,jobs::add,clock,true);
        collector.trigger("BTCUSDT"); jobs.removeFirst().run();
        verify(client,times(12)).fetch(anyString(),any(),any(),any(),any());
    }
    @Test void missingKeyIsIsolatedAndOtherStreamsStillSave() {
        when(client.fetch(eq("BTCUSDT"),eq(MetricType.TOP_ACCOUNT_RATIO),any(),any(),any()))
                .thenThrow(new MetricException("API_KEY_REQUIRED","key needed",300,false));
        collector.trigger("BTCUSDT"); jobs.removeFirst().run();
        verify(repository).fail(eq(1L),eq(MetricType.TOP_ACCOUNT_RATIO),any(),any(),eq(1),any());
        verify(repository).save(eq(1L),eq(MetricType.TOP_POSITION_RATIO),anyList(),any(),any());
        verify(repository,times(5)).save(anyLong(),any(),anyList(),any(),any());
    }
    @Test void rateLimitStopsRequestsAcrossSymbolsAndSurvivesCollectorRestart() {
        when(client.fetch(anyString(),any(),any(),any(),any())).thenThrow(new MetricException("RATE_LIMIT","rate limited",172800,true));
        collector.trigger("BTCUSDT"); collector.trigger("ETHUSDT");
        jobs.removeFirst().run(); jobs.removeFirst().run();
        verify(client,times(1)).fetch(anyString(),any(),any(),any(),any());
        assertThat(gate.get().until()).isEqualTo(NOW.plusSeconds(172800));
        collector = new MetricCollector(client,repository,instruments,jobs::add,clock,true);
        assertThatThrownBy(() -> collector.trigger("ETHUSDT")).isInstanceOf(MetricException.class);
        assertThat(collector.running("ETHUSDT")).isFalse();
    }
    @Test void alignsNextRunToBucketBoundaryAndRepairsSparseWindowsSooner() {
        Instant completed = Instant.parse("2026-11-27T12:02:17Z");
        assertThat(MetricCollector.nextCollection(MetricType.OPEN_INTEREST,288,completed)).isEqualTo(Instant.parse("2026-11-27T12:05:30Z"));
        assertThat(MetricCollector.nextCollection(MetricType.OPEN_INTEREST,287,completed)).isEqualTo(completed.plusSeconds(60));
        assertThat(MetricCollector.nextCollection(MetricType.FUNDING_SNAPSHOT,1,completed)).isEqualTo(Instant.parse("2026-11-27T12:05:30Z"));
    }
    @Test void failedStorageDoesNotMarkStreamSuccessfulOrBlockOtherStreams() {
        doThrow(new IllegalStateException("storage failure")).when(repository).save(eq(1L),eq(MetricType.OPEN_INTEREST),anyList(),any(),any());
        collector.trigger("BTCUSDT"); jobs.removeFirst().run();
        verify(repository).fail(eq(1L),eq(MetricType.OPEN_INTEREST),any(),any(),eq(1),argThat(error -> error.code.equals("PERSISTENCE_ERROR")));
        verify(repository).save(eq(1L),eq(MetricType.FUNDING_HISTORY),anyList(),any(),any());
        assertThat(collector.running("BTCUSDT")).isFalse();
    }
}
