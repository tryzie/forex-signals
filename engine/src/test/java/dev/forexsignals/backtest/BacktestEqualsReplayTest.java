package dev.forexsignals.backtest;

import dev.forexsignals.market.Tick;
import dev.forexsignals.risk.Rig;
import dev.forexsignals.signal.Signal;
import dev.forexsignals.strategy.StrategyRegistry;
import dev.forexsignals.strategy.api.Strategy;
import dev.forexsignals.strategy.impl.RandomControl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 criterion B5 (PLAN §14 regression): backtest == replay. The exact tick stream a backtest fed to the engine
 * is replayed into a fresh engine the way replay/live do; every signal, trade and rejection count must be identical.
 */
class BacktestEqualsReplayTest {

    static Map<String, M1Series> data;

    @BeforeAll
    static void data() { data = EngineRig.m1(List.of("EURUSD", "USDJPY", "XAUUSD"), EngineRig.FROM, EngineRig.TO, 21); }

    static List<Strategy> strategies() {
        List<Strategy> s = new ArrayList<>();
        var all = StrategyRegistry.discover();
        for (String n : List.of("ema_pullback", "donchian_breakout", "rsi_mean_reversion")) s.add(StrategyRegistry.byName(all, n));
        s.add(new RandomControl(StrategyRegistry.byName(all, "ema_pullback"), 0.02)); // guarantees plenty of trades
        return s;
    }

    @Test
    void identicalSignalsTradesAndRejections() {
        Engine bt = EngineRig.engine(strategies(), Rig.costs(), data, 7);
        List<Tick> tape = EngineRig.backtest(bt, data, EngineRig.FROM, EngineRig.TO);

        Engine rp = EngineRig.engine(strategies(), Rig.costs(), data, 7);
        EngineRig.replay(rp, tape, EngineRig.TO);

        List<Signal> a = bt.signals(), b = rp.signals();
        assertThat(a.size()).as("the run must actually trade").isGreaterThan(20);
        assertThat(a.stream().filter(s -> s.state == Signal.State.CLOSED).count()).isGreaterThan(10);
        assertThat(EngineRig.fingerprint(b)).isEqualTo(EngineRig.fingerprint(a));
        // Targets are rounded away from the reference: a 1.5R strategy is never rejected for R:R below 1.5.
        assertThat(bt.rejections().keySet()).noneMatch(k -> k.endsWith("RR_TOO_LOW"));
        assertThat(rp.rejections()).isEqualTo(bt.rejections());
        assertThat(rp.proposals()).isEqualTo(bt.proposals());
    }

    @Test
    void noStrategyIsEverDeliveredInPhase2() {
        assertThat(Engine.STRATEGIES_LIVE).isFalse();
        Engine bt = EngineRig.engine(strategies(), Rig.costs(), data, 7);
        EngineRig.backtest(bt, data, EngineRig.FROM, EngineRig.TO);
        assertThat(bt.signals()).isNotEmpty().allSatisfy(s -> assertThat(s.delivered).isFalse());
    }
}
