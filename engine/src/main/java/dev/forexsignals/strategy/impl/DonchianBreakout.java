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
import static dev.forexsignals.strategy.api.IndicatorKind.DONCHIAN;
import static dev.forexsignals.strategy.api.IndicatorKind.EMA;

/**
 * (b) Donchian breakout with volatility filter, PLAN §7.3. Long (short mirrored):
 *  context   last closed slow-tf close above EMA50(slow)
 *  filter    0.8 <= ATR14 / ATR100 (entry tf) <= 2.0
 *  trigger   close > highest high of the 20 PREVIOUS closed bars (signal bar excluded)
 *  stop      the 10-bar low, but between 1.0 and 1.5 ATR from the reference;  target 2.0R
 *  trailing  stop follows the 10-bar Donchian low on each close (tighten only);  time stop 48 bars
 *  invalidation (failed breakout): within the first 4 bars after entry, a close back below the broken 20-bar level
 */
@StrategyDef
public class DonchianBreakout implements Baseline {

    private final Timeframes tf;
    private final StrategyMeta meta;

    public DonchianBreakout() { this(Timeframes.M15); }

    protected DonchianBreakout(Timeframes tf) {
        this.tf = tf;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("donchianEntry", 20); p.put("donchianTrail", 10); p.put("atrPeriod", 14); p.put("atrRegimeLong", 100);
        p.put("volRatioMin", 0.8); p.put("volRatioMax", 2.0); p.put("emaTrend", 50); p.put("stopMinAtr", 1.0); p.put("stopMaxAtr", 1.5);
        p.put("targetR", 2.0); p.put("timeStopBars", 48); p.put("failedBreakoutBars", 4);
        this.meta = new StrategyMeta("donchian_breakout" + tf.suffix(), "1", "baseline",
                List.of("EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "XAUUSD", "XAGUSD"), tf.entry(), List.of(tf.slow()), p,
                List.of(new IndicatorRequest(tf.entry(), ATR, 14), new IndicatorRequest(tf.entry(), ATR, 100),
                        new IndicatorRequest(tf.entry(), DONCHIAN, 20), new IndicatorRequest(tf.entry(), DONCHIAN, 10),
                        new IndicatorRequest(tf.slow(), EMA, 50)));
    }

    @Override public StrategyMeta meta() { return meta; }

    @Override
    public Optional<SignalProposal> onCandleClose(StrategyContext ctx) {
        List<Reason> why = new ArrayList<>();
        OptionalDouble slowEma = ctx.ind(tf.slow(), EMA, 50), atr = ctx.ind(tf.entry(), ATR, 14), atrLong = ctx.ind(tf.entry(), ATR, 100);
        OptionalDouble hi20 = ctx.donchianHigh(tf.entry(), 20, true), lo20 = ctx.donchianLow(tf.entry(), 20, true);
        if (slowEma.isEmpty() || atr.isEmpty() || atrLong.isEmpty() || hi20.isEmpty() || lo20.isEmpty()) return Optional.empty();
        double ratio = atr.getAsDouble() / atrLong.getAsDouble();
        boolean volOk = ratio >= 0.8 && ratio <= 2.0;
        why.add(new Reason("ATR14/ATR100 in [0.8, 2.0]", ratio, volOk ? 0.8 : 2.0, volOk));
        if (!volOk) return Optional.empty();
        Candle sig = ctx.bar(tf.entry(), 0);
        double slowClose = ctx.bar(tf.slow(), 0).close();
        Direction d;
        double broken;
        if (sig.close() > hi20.getAsDouble() && slowClose > slowEma.getAsDouble()) { d = Direction.LONG; broken = hi20.getAsDouble(); }
        else if (sig.close() < lo20.getAsDouble() && slowClose < slowEma.getAsDouble()) { d = Direction.SHORT; broken = lo20.getAsDouble(); }
        else return Optional.empty();
        why.add(new Reason(tf.slow() + " close on breakout side of EMA50", slowClose, slowEma.getAsDouble(), true));
        why.add(new Reason("close beyond previous 20-bar channel", sig.close(), broken, true));
        return Optional.of(exitPlan(ctx, d, why, broken));
    }

    /** Random control: the "broken" level for the failed-breakout rule is the 20-bar channel on the random side. */
    @Override
    public SignalProposal randomEntryPlan(StrategyContext ctx, Direction d, List<Reason> why) {
        OptionalDouble level = d == Direction.LONG ? ctx.donchianHigh(tf.entry(), 20, true) : ctx.donchianLow(tf.entry(), 20, true);
        return exitPlan(ctx, d, why, level.orElse(Double.NaN));
    }

    SignalProposal exitPlan(StrategyContext ctx, Direction d, List<Reason> why, double brokenLevel) {
        double atr = ctx.ind(tf.entry(), ATR, 14).orElseThrow();
        double structure = d == Direction.LONG ? ctx.donchianLow(tf.entry(), 10, false).orElseThrow() : ctx.donchianHigh(tf.entry(), 10, false).orElseThrow();
        return new SignalProposal(d, StopRule.structure(atr, structure, 1.0, 1.5), 2.0, 48, why,
                "Stop at the 10-bar extreme, clamped to 1.0-1.5 ATR; target 2R; trail the 10-bar Donchian (tighten only); time stop 48 bars.",
                "Within 4 bars after entry, a close back inside the broken 20-bar level -> exit now.",
                Double.isNaN(brokenLevel) ? Map.of() : Map.of("broken", brokenLevel));
    }

    @Override
    public Optional<ExitDecision> onOpenSignalCandleClose(StrategyContext ctx, OpenTradeView open) {
        int s = open.direction().sign();
        Double broken = open.memo().get("broken");
        Candle bar = ctx.bar(tf.entry(), 0);
        if (broken != null && open.barsSinceEntry() <= 4 && s * (bar.close() - broken) < 0) {
            return Optional.of(ExitDecision.exitNow("failed breakout: close back inside the 20-bar level",
                    List.of(new Reason("close vs broken level", bar.close(), broken, false))));
        }
        OptionalDouble trail = s > 0 ? ctx.donchianLow(tf.entry(), 10, false) : ctx.donchianHigh(tf.entry(), 10, false);
        return trail.isPresent() ? Optional.of(ExitDecision.moveStop(trail.getAsDouble(), "10-bar Donchian trail")) : Optional.empty();
    }
}
