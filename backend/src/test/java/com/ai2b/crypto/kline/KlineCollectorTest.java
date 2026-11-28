package com.ai2b.crypto.kline;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import com.ai2b.crypto.instrument.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KlineCollectorTest {
    private final BinanceKlineClient client = mock(BinanceKlineClient.class);
    private final KlineRepository repository = mock(KlineRepository.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T12:00:05Z"));
    private final Instrument instrument = new Instrument(1L, "BINANCE", "USD_M_FUTURES", "BTCUSDT", "BTC", "USDT",
            "USDT", "PERPETUAL", "TRADING", BigDecimal.ONE, BigDecimal.ONE, clock.instant());

    static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static Kline candle(Instant open) {
        return new Kline(open, open.plusMillis(59999), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ONE, BigDecimal.TEN, 1, BigDecimal.ONE, BigDecimal.TEN, open.plusSeconds(61));
    }

    @BeforeEach
    void defaults() {
        when(repository.findInstrument("BTCUSDT")).thenReturn(Optional.of(instrument));
        when(repository.checkpoint(anyString())).thenReturn(Optional.empty());
        when(client.serverTime()).thenAnswer(ignored -> clock.instant());
    }

    private KlineCollector service(Executor executor) {
        return new KlineCollector(client, repository, executor, clock, false, 0);
    }

    @Test
    void backfills24HoursInBoundedPagesAndRestartResumesAtNextMinute() {
        AtomicReference<KlineRepository.Checkpoint> checkpoint = new AtomicReference<>();
        when(repository.checkpoint("BTCUSDT")).thenAnswer(ignored -> Optional.ofNullable(checkpoint.get()));
        when(client.fetch(eq("BTCUSDT"), any(), any(), any())).thenAnswer(call -> List.of(candle(call.getArgument(1))));
        when(repository.savePage(any(), anyList(), any(), any(), any(), any())).thenAnswer(call -> {
            checkpoint.set(new KlineRepository.Checkpoint(call.getArgument(2), call.getArgument(4), clock.instant()));
            return call.getArgument(4);
        });
        var collector = service(Runnable::run);
        assertThat(collector.trigger("BTCUSDT").outcome()).isEqualTo("SUCCESS");
        Instant end = KlineCollector.latestClosedOpen(clock.instant().minusSeconds(2));
        assertThat(checkpoint.get().start()).isEqualTo(end.minusSeconds(1439 * 60L));
        assertThat(checkpoint.get().contiguousOpen()).isEqualTo(end);
        verify(client, times(3)).fetch(eq("BTCUSDT"), any(), any(), any());
        clock.advance(120);
        var restarted = service(Runnable::run);
        restarted.trigger("BTCUSDT");
        verify(client).fetch(eq("BTCUSDT"), eq(end.plusSeconds(60)), eq(end.plusSeconds(120)), any());
        assertThat(checkpoint.get().start()).isEqualTo(end.minusSeconds(1439 * 60L));
    }

    @Test
    void sparsePageStopsAtGapAndNextAttemptStartsBeforeMissingMinute() {
        Instant start = clock.instant().minusSeconds(365).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        AtomicReference<KlineRepository.Checkpoint> checkpoint = new AtomicReference<>(new KlineRepository.Checkpoint(start, start, start));
        when(repository.checkpoint("BTCUSDT")).thenAnswer(ignored -> Optional.of(checkpoint.get()));
        when(client.fetch(eq("BTCUSDT"), any(), any(), any())).thenAnswer(call -> List.of(candle(call.getArgument(1))));
        when(repository.savePage(any(), anyList(), any(), any(), any(), any())).thenAnswer(call -> {
            Instant last = call.getArgument(3);
            checkpoint.set(new KlineRepository.Checkpoint(start, last, clock.instant()));
            return last;
        });
        var collector = service(Runnable::run);
        assertThat(collector.trigger("BTCUSDT").error()).contains("缺失");
        assertThat(checkpoint.get().contiguousOpen()).isEqualTo(start.plusSeconds(60));
        clock.advance(31);
        collector.trigger("BTCUSDT");
        verify(client).fetch(eq("BTCUSDT"), eq(start.plusSeconds(120)), any(), any());
    }

    @Test
    void networkFailureKeepsCheckpointAndBacksOffAcrossSymbols() {
        when(client.serverTime()).thenThrow(new KlineException("UPSTREAM_UNAVAILABLE", "交易所返回 HTTP 451", 300));
        var collector = service(Runnable::run);
        assertThat(collector.trigger("BTCUSDT").outcome()).isEqualTo("ERROR");
        assertThatThrownBy(() -> collector.trigger("ETHUSDT")).isInstanceOf(KlineException.class);
        assertThat(collector.status("ETHUSDT").error()).contains("451");
        assertThat(collector.status("BTCUSDT").nextAllowedAt()).isEqualTo(clock.instant().plusSeconds(300));
        verify(repository, never()).savePage(any(), anyList(), any(), any(), any(), any());
    }

    @Test
    void queueRejectsOverlappingJobsAndDisabledSchedulerDoesNothing() {
        List<Runnable> queued = new ArrayList<>();
        var collector = service(queued::add);
        collector.scheduledCollect();
        assertThat(queued).isEmpty();
        assertThat(collector.trigger("BTCUSDT").outcome()).isEqualTo("QUEUED");
        assertThatThrownBy(() -> collector.trigger("BTCUSDT")).isInstanceOf(KlineException.class);
        assertThat(queued).hasSize(1);
        when(client.serverTime()).thenThrow(new KlineException("NETWORK_UNAVAILABLE", "网络不可用", 30));
        queued.getFirst().run();
        assertThat(collector.status("BTCUSDT").running()).isFalse();
    }

    @Test
    void clockSkewAndUnsupportedSymbolNeverWriteData() {
        when(client.serverTime()).thenReturn(clock.instant().plusSeconds(600));
        var collector = service(Runnable::run);
        assertThat(collector.trigger("BTCUSDT").error()).contains("时间偏差");
        assertThatThrownBy(() -> collector.trigger("OTHERUSDT")).isInstanceOf(KlineException.class);
        verify(client, never()).fetch(anyString(), any(), any(), any());
        verify(repository, never()).savePage(any(), anyList(), any(), any(), any(), any());
    }
}
