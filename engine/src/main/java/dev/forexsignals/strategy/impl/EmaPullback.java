package dev.forexsignals.strategy.impl;

import dev.forexsignals.market.Candle;
import dev.forexsignals.strategy.api.Direction;
import dev.forexsignals.strategy.api.ExitDecision;
import dev.forexsignals.strategy.api.IndicatorRequest;
import dev.forexsignals.strategy.api.OpenTradeView;
import dev.forexsignals.strategy.api.Reason;
import dev.forexsignals.strategy.api.SignalProposal;
import dev.forexsignals.strategy.api.StopRule;
import dev.forexsignals.strategy.api.Strategy;
import dev.forexsignals.strategy.api.StrategyContext;
import dev.forexsignals.strategy.api.StrategyDef;
import dev.forexsignals.strategy.api.StrategyMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static dev.forexsignals.strategy.api.IndicatorKind.ATR;
import static dev.forexsignals.strategy.api.IndicatorKind.EMA;

/**
 * (a) EMA trend pullback, PLAN §7.3. Long (short mirrored):
 *  context   last closed slow-tf close > EMA50(slow), and EMA50(fast) now > EMA50(fast) 5 bars ago
 *  setup     EMA20 > EMA50 on the entry tf, and in the last 3 closed bars some low <= EMA20 + 0.25 ATR14
 *  trigger   the closed bar has close > EMA20 and close > open
 *  stop      1.5 ATR14;  target 2.0R;  trailing: breakeven at +1R, then chandelier (best bid - 2.5 ATR), tighten only
 *  time stop 32 bars;  invalidation: a closed fast-tf bar finishes on the wrong side of EMA50(fast) -> exit now
 */
@StrategyDef
public class EmaPullback implements Baseline {

    static final Map<String, Object> PARAMS = params();
    private final Timeframes tf;
    private final StrategyMeta meta;

    public EmaPullback() { this(Timeframes.M15); }

    protected EmaPullback(Timeframes tf) {
        this(tf, "ema_pullback" + tf.suffix(), List.of("EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "XAUUSD", "XAGUSD"));
    }

    /** Same rules and parameters; only the name and the symbol list differ (e.g. the EURUSD-only hypothesis variants). */
    protected EmaPullback(Timeframes tf, String name, List<String> symbols) {
        this.tf = tf;
        this.meta = new StrategyMeta(name, "1", "baseline", symbols, tf.entry(), List.of(tf.fast(), tf.slow()), PARAMS,
                List.of(new IndicatorRequest(tf.entry(), EMA, 20), new IndicatorRequest(tf.entry(), EMA, 50), new IndicatorRequest(tf.entry(), ATR, 14),
                        new IndicatorRequest(tf.fast(), EMA, 50), new IndicatorRequest(tf.slow(), EMA, 50)));
    }

    private static Map<String, Object> params() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("emaFast", 20); p.put("emaSlow", 50); p.put("emaTrend", 50); p.put("atrPeriod", 14);
        p.put("pullbackTouchAtr", 0.25); p.put("slopeBars", 5); p.put("stopAtr", 1.5); p.put("targetR", 2.0);
        p.put("beAtR", 1.0); p.put("trailAtr", 2.5); p.put("timeStopBars", 32);
        return p;
    }

    @Override public StrategyMeta meta() { return meta; }

    /** Context filters only (the random control drops these). Empty if any value is not available yet. */
    Optional<Direction> context(StrategyContext ctx, List<Reason> why) {
        OptionalDouble slowEma = ctx.ind(tf.slow(), EMA, 50), fastNow = ctx.ind(tf.fast(), EMA, 50), fastThen = ctx.ind(tf.fast(), EMA, 50, 5);
        if (slowEma.isEmpty() || fastNow.isEmpty() || fastThen.isEmpty()) return Optional.empty();
        double slowClose = ctx.bar(tf.slow(), 0).close();
        boolean up = slowClose > slowEma.getAsDouble() && fastNow.getAsDouble() > fastThen.getAsDouble();
        boolean down = slowClose < slowEma.getAsDouble() && fastNow.getAsDouble() < fastThen.getAsDouble();
        why.add(new Reason(tf.slow() + " close vs EMA50(" + tf.slow() + ")", slowClose, slowEma.getAsDouble(), up || down));
        why.add(new Reason("EMA50(" + tf.fast() + ") now vs 5 bars ago", fastNow.getAsDouble(), fastThen.getAsDouble(), up || down));
        return up ? Optional.of(Direction.LONG) : down ? Optional.of(Direction.SHORT) : Optional.empty();
    }

    @Override
    public Optional<SignalProposal> onCandleClose(StrategyContext ctx) {
        List<Reason> why = new ArrayList<>();
        Optional<Direction> dir = context(ctx, why);
        if (dir.isEmpty()) return Optional.empty();
        OptionalDouble e20 = ctx.ind(tf.entry(), EMA, 20), e50 = ctx.ind(tf.entry(), EMA, 50), atr = ctx.ind(tf.entry(), ATR, 14);
        if (e20.isEmpty() || e50.isEmpty() || atr.isEmpty()) return Optional.empty();
        int s = dir.get().sign();
        boolean stack = s * (e20.getAsDouble() - e50.getAsDouble()) > 0;
        why.add(new Reason("EMA20 vs EMA50 (" + tf.entry() + ")", e20.getAsDouble(), e50.getAsDouble(), stack));
        if (!stack) return Optional.empty();
        // Pullback: in the last 3 closed bars, a low within 0.25 ATR of (or through) EMA20 (highs for shorts).
        boolean touched = false;
        for (int ago = 0; ago < 3; ago++) {
            Candle b = ctx.bar(tf.entry(), ago);
            OptionalDouble e = ctx.ind(tf.entry(), EMA, 20, ago);
            if (e.isEmpty()) continue;
            double band = e.getAsDouble() + s * 0.25 * atr.getAsDouble();
            if (s > 0 ? b.low() <= band : b.high() >= band) touched = true;
        }
        why.add(new Reason("pullback to EMA20 +/- 0.25 ATR in last 3 bars", touched ? 1 : 0, 1, touched));
        if (!touched) return Optional.empty();
        Candle sig = ctx.bar(tf.entry(), 0);
        boolean trigger = s * (sig.close() - e20.getAsDouble()) > 0 && s * (sig.close() - sig.open()) > 0;
        why.add(new Reason("close beyond EMA20 and candle in trend direction", sig.close(), e20.getAsDouble(), trigger));
        if (!trigger) return Optional.empty();
        return Optional.of(exitPlan(ctx, dir.get(), why));
    }

    @Override
    public SignalProposal randomEntryPlan(StrategyContext ctx, Direction d, List<Reason> why) { return exitPlan(ctx, d, why); }

    /** The parent's exits, also used by the random control for a random direction. */
    SignalProposal exitPlan(StrategyContext ctx, Direction d, List<Reason> why) {
        double atr = ctx.ind(tf.entry(), ATR, 14).orElseThrow();
        return new SignalProposal(d, StopRule.atrMultiple(atr, 1.5), 2.0, 32, why,
                "Stop 1.5 ATR, target 2R. At +1R move stop to breakeven, then trail 2.5 ATR behind the best price (tighten only). Time stop 32 bars.",
                "A closed " + tf.fast() + " bar finishes on the wrong side of EMA50(" + tf.fast() + ") -> exit now.", Map.of("atr", atr));
    }

    @Override
    public Optional<ExitDecision> onOpenSignalCandleClose(StrategyContext ctx, OpenTradeView open) {
        int s = open.direction().sign();
        // Invalidation: the latest closed fast-tf bar (closed after entry) on the wrong side of EMA50(fast).
        Candle fast = ctx.bar(tf.fast(), 0);
        OptionalDouble e50 = ctx.ind(tf.fast(), EMA, 50);
        if (fast.closeTime().isAfter(open.filledAt()) && e50.isPresent() && s * (fast.close() - e50.getAsDouble()) < 0) {
            return Optional.of(ExitDecision.exitNow("invalidation: " + tf.fast() + " close beyond EMA50",
                    List.of(new Reason(tf.fast() + " close vs EMA50", fast.close(), e50.getAsDouble(), false))));
        }
        OptionalDouble atr = ctx.ind(tf.entry(), ATR, 14);
        if (open.mfeR() >= 1.0 && atr.isPresent()) {
            // Breakeven at +1R (the reference price), then the chandelier trail; the tracker keeps only tighter stops.
            double chandelier = open.bestPriceSinceEntry() - s * 2.5 * atr.getAsDouble();
            double level = s > 0 ? Math.max(open.reference(), chandelier) : Math.min(open.reference(), chandelier);
            return Optional.of(ExitDecision.moveStop(level, "breakeven at +1R / chandelier 2.5 ATR"));
        }
        return Optional.empty();
    }
}
