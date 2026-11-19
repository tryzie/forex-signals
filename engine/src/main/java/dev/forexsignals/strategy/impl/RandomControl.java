package dev.forexsignals.strategy.impl;

import dev.forexsignals.strategy.api.Direction;
import dev.forexsignals.strategy.api.ExitDecision;
import dev.forexsignals.strategy.api.OpenTradeView;
import dev.forexsignals.strategy.api.Reason;
import dev.forexsignals.strategy.api.SignalProposal;
import dev.forexsignals.strategy.api.Strategy;
import dev.forexsignals.strategy.api.StrategyContext;
import dev.forexsignals.strategy.api.StrategyMeta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Random-entry control (PLAN §7.3 d): exactly the parent's symbols, timeframes, warm-up, sessions/risk gate (applied by
 * the engine), stop, target, trailing, time stop and invalidation (applied to the random direction). The parent's
 * entry filters are dropped: on each eligible closed bar it fires with probability = the parent's signals per
 * eligible bar measured in the same backtest, in a random direction, using the run's seeded RNG.
 * Not discovered by the registry: the engine builds it once the parent's rate is known.
 */
public final class RandomControl implements Strategy {

    private final Baseline parent;
    private final double probability;
    private final StrategyMeta meta;

    public RandomControl(Strategy parent, double probability) {
        if (!(parent instanceof Baseline b)) throw new IllegalArgumentException(parent.meta().id() + " has no borrowable exits");
        if (probability < 0 || probability > 1) throw new IllegalArgumentException("probability " + probability);
        this.parent = b;
        this.probability = probability;
        StrategyMeta pm = parent.meta();
        Map<String, Object> p = new LinkedHashMap<>(pm.params());
        p.put("controlOf", pm.id());
        p.put("probability", probability);
        this.meta = new StrategyMeta(pm.name() + "__random", pm.version(), "control of " + pm.id(), pm.symbols(), pm.entryTf(),
                pm.contextTfs(), p, pm.indicators());
    }

    public static String controlName(String parentName) { return parentName + "__random"; }

    @Override public StrategyMeta meta() { return meta; }

    @Override
    public Optional<SignalProposal> onCandleClose(StrategyContext ctx) {
        if (ctx.random().nextDouble() >= probability) return Optional.empty();
        Direction d = ctx.random().nextBoolean() ? Direction.LONG : Direction.SHORT;
        return Optional.of(parent.randomEntryPlan(ctx, d, List.of(new Reason("random entry (p per eligible bar)", probability, probability, true))));
    }

    @Override
    public Optional<ExitDecision> onOpenSignalCandleClose(StrategyContext ctx, OpenTradeView open) {
        return parent.onOpenSignalCandleClose(ctx, open);
    }
}
