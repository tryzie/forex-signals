package dev.forexsignals.strategy;

import dev.forexsignals.market.Candle;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.strategy.api.ExitDecision;
import dev.forexsignals.strategy.api.IndicatorKind;
import dev.forexsignals.strategy.api.IndicatorRequest;
import dev.forexsignals.strategy.api.LookaheadViolation;
import dev.forexsignals.strategy.api.OpenTradeView;
import dev.forexsignals.strategy.api.SignalProposal;
import dev.forexsignals.strategy.api.Strategy;
import dev.forexsignals.strategy.api.StrategyContext;
import dev.forexsignals.strategy.api.StrategyMeta;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 criterion C6: deliberately injected lookahead must be caught by the guard. If any of these stopped throwing,
 * this test (and the build) fails.
 */
class LookaheadGuardTest {

    /** A strategy that runs arbitrary "peeking" code at each candle close. */
    static Strategy peeker(Consumer<StrategyContext> peek) {
        StrategyMeta meta = new StrategyMeta("peeker", "1", "test", List.of("EURUSD"), Timeframe.M15, List.of(Timeframe.H1), Map.of(),
                List.of(new IndicatorRequest(Timeframe.M15, IndicatorKind.EMA, 3)));
        return new Strategy() {
            @Override public StrategyMeta meta() { return meta; }
            @Override public Optional<SignalProposal> onCandleClose(StrategyContext ctx) { peek.accept(ctx); return Optional.empty(); }
            @Override public Optional<ExitDecision> onOpenSignalCandleClose(StrategyContext ctx, OpenTradeView o) { return Optional.empty(); }
        };
    }

    private static Series warmSeries() {
        return new Series("EURUSD").add(Timeframe.M15, Series.trend(40, 1.1, 0.0001, 0.00005)).add(Timeframe.H1, Series.trend(20, 1.1, 0.0004, 0.0001));
    }

    @Test
    void strategyPeekingAtTheNextBarIsCaught() {
        assertThatThrownBy(() -> warmSeries().run(peeker(ctx -> ctx.bar(Timeframe.M15, -1))))
                .isInstanceOf(LookaheadViolation.class).hasMessageContaining("future bar");
    }

    @Test
    void strategyPeekingAtTheNextIndicatorValueIsCaught() {
        assertThatThrownBy(() -> warmSeries().run(peeker(ctx -> ctx.ind(Timeframe.M15, IndicatorKind.EMA, 3, -1))))
                .isInstanceOf(LookaheadViolation.class).hasMessageContaining("future indicator");
    }

    @Test
    void undeclaredTimeframeOrIndicatorIsRefused() {
        assertThatThrownBy(() -> warmSeries().run(peeker(ctx -> ctx.bar(Timeframe.H4, 0)))).isInstanceOf(LookaheadViolation.class);
        assertThatThrownBy(() -> warmSeries().run(peeker(ctx -> ctx.ind(Timeframe.M15, IndicatorKind.RSI, 14)))).isInstanceOf(LookaheadViolation.class);
    }

    @Test
    void engineFaultFeedingAnUnclosedBarIsCaught() {
        // Simulated engine bug: at 15:00 the batch also carries the H1 bar 15:00-16:00, which only closes at 16:00
        // (a "vectorised backtest" style leak of the forming bar). A strategy reading H1 would see the future.
        StrategyRunner runner = new StrategyRunner(List.of(peeker(ctx -> ctx.bar(Timeframe.H1, 0))), 1);
        Instant at = Instant.parse("2024-03-06T15:00:00Z");
        Candle m15 = new Candle("test", "EURUSD", Timeframe.M15, at.minusSeconds(900), at, 1, 1, 1, 1, 1, Double.NaN, Double.NaN, Double.NaN, Candle.Source.DERIVED);
        Candle leaked = new Candle("test", "EURUSD", Timeframe.H1, at, at.plusSeconds(3600), 1, 1, 1, 1, 1, Double.NaN, Double.NaN, Double.NaN, Candle.Source.DERIVED);
        assertThatThrownBy(() -> runner.onCandlesClosed(at, List.of(m15, leaked), (id, s) -> Map.of()))
                .isInstanceOf(LookaheadViolation.class).hasMessageContaining("unclosed candle");
    }

    @Test
    void honestStrategyReadingClosedDataIsFine() {
        var r = warmSeries().run(peeker(ctx -> {
            assertThat(ctx.bar(Timeframe.M15, 0).closeTime()).isEqualTo(ctx.now());
            assertThat(ctx.bar(Timeframe.H1, 0).closeTime()).isBeforeOrEqualTo(ctx.now());
        }));
        assertThat(r.proposals()).isEmpty();
    }
}
