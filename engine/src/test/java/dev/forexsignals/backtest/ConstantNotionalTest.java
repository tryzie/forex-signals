package dev.forexsignals.backtest;

import dev.forexsignals.market.PriceBasis;
import dev.forexsignals.risk.Rig;
import dev.forexsignals.risk.RiskGate;
import dev.forexsignals.strategy.StrategyRegistry;
import dev.forexsignals.strategy.impl.RandomControl;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Validation runs size on a constant notional: a losing strategy's later trades are not censored by SIZE_BELOW_MIN. */
class ConstantNotionalTest {
    @Test
    void lotsStayAtTheNotionalSizeWhileEquityFalls() {
        var data = EngineRig.m1(List.of("EURUSD", "USDJPY"), EngineRig.FROM, EngineRig.TO, 12);
        var loser = new RandomControl(StrategyRegistry.byName(StrategyRegistry.discover(), "ema_pullback"), 0.05);
        var costs = Rig.costs();
        Engine e = new Engine(new Engine.Settings("synthetic", PriceBasis.BID, Duration.ofSeconds(2), Duration.ofSeconds(10), 10_000, 1, false, true, true),
                List.of(loser), Rig.CATALOG, new RiskGate(RiskGate.Config.defaults(), costs, null), costs, (s, t) -> BacktestRunner.at(data, s, t), s -> true);
        BacktestRunner.run(e, data, Rig.spreads(Rig.MEDIANS), EngineRig.FROM, EngineRig.TO, null);
        assertThat(e.rejectionsBySymbol().values().stream().flatMap(m -> m.keySet().stream())).doesNotContain("SIZE_BELOW_MIN", "KILL_SWITCH");
        // Risk per trade stays about 1% of the notional, never shrinking with the equity.
        assertThat(e.signals()).isNotEmpty().allSatisfy(s -> assertThat(s.riskUsd).isBetween(80.0, 100.0));
    }
}
