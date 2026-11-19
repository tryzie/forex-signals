package dev.forexsignals.strategy.impl;

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

import static dev.forexsignals.strategy.api.IndicatorKind.ADX;
import static dev.forexsignals.strategy.api.IndicatorKind.ATR;
import static dev.forexsignals.strategy.api.IndicatorKind.RSI;

/**
 * (c) RSI mean reversion in a range, PLAN §7.3. FX MAJORS ONLY (never metals, USDX or the data-only pairs).
 *  regime    ADX14 on the last closed fast-tf bar (H1 for M15 entries) < 20
 *  trigger   long: RSI14 previous bar < 30 and signal bar >= 30;  short: > 70 then <= 70
 *  stop      1.5 ATR14;  target 1.5R;  trailing: none (deliberate);  time stop 16 bars
 *  invalidation: ADX14 on a closed fast-tf bar >= 25 -> exit now
 */
@StrategyDef
public class RsiMeanReversion implements Baseline {

    private final Timeframes tf;
    private final StrategyMeta meta;

    public RsiMeanReversion() { this(Timeframes.M15); }

    protected RsiMeanReversion(Timeframes tf) {
        this.tf = tf;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("rsiPeriod", 14); p.put("rsiLow", 30); p.put("rsiHigh", 70); p.put("adxPeriod", 14); p.put("adxRange", 20);
        p.put("adxInvalidate", 25); p.put("atrPeriod", 14); p.put("stopAtr", 1.5); p.put("targetR", 1.5); p.put("timeStopBars", 16);
        this.meta = new StrategyMeta("rsi_mean_reversion" + tf.suffix(), "1", "baseline",
                List.of("EURUSD", "GBPUSD", "USDJPY", "AUDUSD"), tf.entry(), List.of(tf.fast()), p,
                List.of(new IndicatorRequest(tf.entry(), RSI, 14), new IndicatorRequest(tf.entry(), ATR, 14), new IndicatorRequest(tf.fast(), ADX, 14)));
    }

    @Override public StrategyMeta meta() { return meta; }

    @Override
    public Optional<SignalProposal> onCandleClose(StrategyContext ctx) {
        List<Reason> why = new ArrayList<>();
        OptionalDouble adx = ctx.ind(tf.fast(), ADX, 14), rsiNow = ctx.ind(tf.entry(), RSI, 14), rsiPrev = ctx.ind(tf.entry(), RSI, 14, 1);
        if (adx.isEmpty() || rsiNow.isEmpty() || rsiPrev.isEmpty()) return Optional.empty();
        boolean range = adx.getAsDouble() < 20;
        why.add(new Reason("ADX14(" + tf.fast() + ") < 20", adx.getAsDouble(), 20, range));
        if (!range) return Optional.empty();
        Direction d;
        if (rsiPrev.getAsDouble() < 30 && rsiNow.getAsDouble() >= 30) d = Direction.LONG;
        else if (rsiPrev.getAsDouble() > 70 && rsiNow.getAsDouble() <= 70) d = Direction.SHORT;
        else return Optional.empty();
        why.add(new Reason("RSI14 previous bar beyond " + (d == Direction.LONG ? "30" : "70"), rsiPrev.getAsDouble(), d == Direction.LONG ? 30 : 70, true));
        why.add(new Reason("RSI14 back inside", rsiNow.getAsDouble(), d == Direction.LONG ? 30 : 70, true));
        return Optional.of(exitPlan(ctx, d, why));
    }

    @Override
    public SignalProposal randomEntryPlan(StrategyContext ctx, Direction d, List<Reason> why) { return exitPlan(ctx, d, why); }

    SignalProposal exitPlan(StrategyContext ctx, Direction d, List<Reason> why) {
        double atr = ctx.ind(tf.entry(), ATR, 14).orElseThrow();
        return new SignalProposal(d, StopRule.atrMultiple(atr, 1.5), 1.5, 16, why,
                "Stop 1.5 ATR, target 1.5R, no trailing (deliberate), time stop 16 bars.",
                "ADX14 on a closed " + tf.fast() + " bar >= 25 -> exit now.", Map.of());
    }

    @Override
    public Optional<ExitDecision> onOpenSignalCandleClose(StrategyContext ctx, OpenTradeView open) {
        OptionalDouble adx = ctx.ind(tf.fast(), ADX, 14);
        if (ctx.bar(tf.fast(), 0).closeTime().isAfter(open.filledAt()) && adx.isPresent() && adx.getAsDouble() >= 25) {
            return Optional.of(ExitDecision.exitNow("invalidation: ADX14 >= 25 (trend started)",
                    List.of(new Reason("ADX14(" + tf.fast() + ")", adx.getAsDouble(), 25, false))));
        }
        return Optional.empty();
    }
}
