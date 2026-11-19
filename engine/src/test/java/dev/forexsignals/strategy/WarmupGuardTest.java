package dev.forexsignals.strategy;

import dev.forexsignals.market.Timeframe;
import dev.forexsignals.strategy.api.Direction;
import dev.forexsignals.strategy.api.ExitDecision;
import dev.forexsignals.strategy.api.IndicatorKind;
import dev.forexsignals.strategy.api.IndicatorRequest;
import dev.forexsignals.strategy.api.OpenTradeView;
import dev.forexsignals.strategy.api.SignalProposal;
import dev.forexsignals.strategy.api.StopRule;
import dev.forexsignals.strategy.api.Strategy;
import dev.forexsignals.strategy.api.StrategyContext;
import dev.forexsignals.strategy.api.StrategyMeta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 2 criterion C9: no signal before every declared timeframe has 3x the longest period + 10 closed bars. */
class WarmupGuardTest {

    @Test
    void anAlwaysSignalStrategyIsSilentUntilWarm() {
        List<Integer> seenAtBar = new ArrayList<>();
        StrategyMeta meta = new StrategyMeta("eager", "1", "test", List.of("EURUSD"), Timeframe.M15, List.of(), Map.of(),
                List.of(new IndicatorRequest(Timeframe.M15, IndicatorKind.EMA, 50)));
        Strategy eager = new Strategy() {
            @Override public StrategyMeta meta() { return meta; }
            @Override public Optional<SignalProposal> onCandleClose(StrategyContext ctx) {
                seenAtBar.add(ctx.bars(Timeframe.M15));
                // Tries to signal unconditionally, even without an indicator value.
                return Optional.of(new SignalProposal(Direction.LONG, StopRule.atrMultiple(0.001, 1), 2, 10, List.of(), "", "", Map.of()));
            }
            @Override public Optional<ExitDecision> onOpenSignalCandleClose(StrategyContext c, OpenTradeView o) { return Optional.empty(); }
        };
        StrategyRunner runner = new StrategyRunner(List.of(eager), 1);
        assertThat(runner.warmupBars("eager@1", Timeframe.M15)).isEqualTo(3 * 50 + 10);
        new Series("EURUSD").add(Timeframe.M15, Series.trend(200, 1.1, 0.0001, 0.00005)).run(runner);
        assertThat(seenAtBar.getFirst()).as("first call only at the 160th closed bar").isEqualTo(160);
        assertThat(runner.warmupBlocked("eager@1")).isEqualTo(159);
        assertThat(runner.evaluated("eager@1")).isEqualTo(41);
    }

    @Test
    void indicatorValuesFromBeforeTheWarmupAreHidden() {
        List<Boolean> presentAgo50 = new ArrayList<>();
        StrategyMeta meta = new StrategyMeta("reader", "1", "test", List.of("EURUSD"), Timeframe.M15, List.of(), Map.of(),
                List.of(new IndicatorRequest(Timeframe.M15, IndicatorKind.EMA, 20)));
        Strategy reader = new Strategy() {
            @Override public StrategyMeta meta() { return meta; }
            @Override public Optional<SignalProposal> onCandleClose(StrategyContext ctx) {
                // At the first warm bar (70), the value 50 bars back was computed when only 20 bars existed: hidden.
                presentAgo50.add(ctx.ind(Timeframe.M15, IndicatorKind.EMA, 20, 50).isPresent());
                return Optional.empty();
            }
            @Override public Optional<ExitDecision> onOpenSignalCandleClose(StrategyContext c, OpenTradeView o) { return Optional.empty(); }
        };
        new Series("EURUSD").add(Timeframe.M15, Series.trend(130, 1.1, 0.0001, 0.00005)).run(reader);
        assertThat(presentAgo50.getFirst()).isFalse();
        assertThat(presentAgo50.getLast()).isTrue(); // at bar 130, 50 back = bar 80 >= 70
    }
}
