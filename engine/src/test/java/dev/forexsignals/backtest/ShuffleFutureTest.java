package dev.forexsignals.backtest;

import dev.forexsignals.risk.Rig;
import dev.forexsignals.signal.Signal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 criterion C7 (PLAN §14 lookahead test): replace every bar after T with a different random path. Everything
 * decided before T (signals, and trades that closed before T) must be identical. Catches lookahead through any channel.
 */
class ShuffleFutureTest {

    @Test
    void signalsAndTradesBeforeTAreUnchangedWhenTheFutureChanges() {
        Map<String, M1Series> real = EngineRig.m1(List.of("EURUSD", "XAUUSD"), EngineRig.FROM, EngineRig.TO, 33);
        Instant cut = Instant.parse("2024-03-01T00:00:00Z");
        Map<String, M1Series> shuffled = new java.util.LinkedHashMap<>();
        Random r = new Random(99);
        real.forEach((sym, s) -> {
            M1Series x = new M1Series(s.feed, s.symbol);
            double drift = 0;
            for (int i = 0; i < s.n; i++) {
                var c = s.candle(i);
                if (!c.openTime().isBefore(cut)) {
                    drift += r.nextGaussian() * c.close() * 0.0004; // a different future path
                    c = new dev.forexsignals.market.Candle(c.feed(), c.symbol(), c.timeframe(), c.openTime(), c.closeTime(),
                            c.open() + drift, c.high() + drift, c.low() + drift, c.close() + drift, 0, Double.NaN, Double.NaN, Double.NaN, c.source());
                }
                x.add(c);
            }
            shuffled.put(sym, x);
        });

        Engine a = EngineRig.engine(BacktestEqualsReplayTest.strategies(), Rig.costs(), real, 5);
        EngineRig.backtest(a, real, EngineRig.FROM, EngineRig.TO);
        Engine b = EngineRig.engine(BacktestEqualsReplayTest.strategies(), Rig.costs(), shuffled, 5);
        EngineRig.backtest(b, shuffled, EngineRig.FROM, EngineRig.TO);

        List<Signal> pastA = a.signals().stream().filter(s -> s.signalAt.isBefore(cut)).toList();
        List<Signal> pastB = b.signals().stream().filter(s -> s.signalAt.isBefore(cut)).toList();
        assertThat(pastA.size()).isGreaterThan(10);
        // Signals decided before T: identical keys, directions, levels.
        assertThat(pastB.stream().map(s -> s.key() + s.direction + s.initialStop + s.target).toList())
                .isEqualTo(pastA.stream().map(s -> s.key() + s.direction + s.initialStop + s.target).toList());
        // Trades fully closed before T: identical results.
        var doneA = EngineRig.fingerprint(pastA.stream().filter(s -> s.closedAt != null && s.closedAt.isBefore(cut)).toList());
        var doneB = EngineRig.fingerprint(pastB.stream().filter(s -> s.closedAt != null && s.closedAt.isBefore(cut)).toList());
        assertThat(doneA).isNotEmpty();
        assertThat(doneB).isEqualTo(doneA);
        // And the future really did change.
        assertThat(EngineRig.fingerprint(b.signals())).isNotEqualTo(EngineRig.fingerprint(a.signals()));
    }
}
