package dev.forexsignals.strategy.impl;

import dev.forexsignals.market.Candle;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.strategy.api.Direction;
import dev.forexsignals.strategy.api.ExitDecision;
import dev.forexsignals.strategy.api.IndicatorKind;
import dev.forexsignals.strategy.api.IndicatorRequest;
import dev.forexsignals.strategy.api.OpenTradeView;
import dev.forexsignals.strategy.api.Reason;
import dev.forexsignals.strategy.api.SignalProposal;
import dev.forexsignals.strategy.api.Strategy;
import dev.forexsignals.strategy.api.StrategyContext;
import dev.forexsignals.strategy.api.StrategyMeta;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Cross-market confirmation gate, pre-registered in docs/hyp/eurusd-confirmation.md §5 (frozen 2026-11-20).
 * Wraps an unchanged strategy; keeps a signal only if the gate agrees with its direction. Never changes stop, target or size.
 *
 *  reference bar  the latest H1 bar whose close is at or before T - 1 minute (T = signal time); same instant H1 bars of
 *                 other symbols are never used, so live and backtest decide identically
 *  up(X)/down(X)  X has a bar with exactly that open time and its close is above / below EMA20(H1) of X at that bar;
 *                 equal is neither; a missing bar does not agree
 *  long           down(USDX) and at least 2 of up(GBPUSD), up(AUDUSD), up(XAUUSD); short mirrored
 * Only the wrapped strategy's symbols are traded; the context symbols are read only.
 */
public class Confirmed implements Baseline {

    public static final String USDX = "USDX";
    public static final List<String> PEERS = List.of("GBPUSD", "AUDUSD", "XAUUSD");
    public static final int EMA_PERIOD = 20;
    public static final int MIN_AGREE = 2;
    public static final Duration REFERENCE_MARGIN = Duration.ofMinutes(1);

    private final Strategy inner;
    private final StrategyMeta meta;

    public Confirmed(String name, Strategy inner) {
        this.inner = inner;
        StrategyMeta m = inner.meta();
        Map<String, Object> p = new LinkedHashMap<>(m.params());
        p.put("confirmation", "USDX vs EMA20(H1) and >= 2 of GBPUSD/AUDUSD/XAUUSD vs EMA20(H1), reference bar closed >= 1 min before signal");
        p.put("confirmationEmaPeriod", EMA_PERIOD);
        p.put("confirmationMinAgree", MIN_AGREE);
        p.put("confirmationMarginSec", REFERENCE_MARGIN.toSeconds());
        p.put("confirmationInner", m.id());
        List<String> ctx = new ArrayList<>();
        ctx.add(USDX);
        ctx.addAll(PEERS);
        this.meta = new StrategyMeta(name, m.version(), "pre-registered: docs/hyp/eurusd-confirmation.md", m.symbols(), m.entryTf(), m.contextTfs(),
                p, m.indicators(), ctx, List.of(new IndicatorRequest(Timeframe.H1, IndicatorKind.EMA, EMA_PERIOD)));
    }

    @Override public StrategyMeta meta() { return meta; }

    /** Open time of the reference H1 bar for a signal at t. */
    public static Instant referenceOpen(Instant t) {
        Instant end = Timeframe.H1.bucketStart(t.minus(REFERENCE_MARGIN)); // end of the latest H1 bar closed by t - 1 min
        return Timeframe.H1.bucketStart(end.minusMillis(1));
    }

    /** +1 up, -1 down, 0 neither or missing. */
    static int state(StrategyContext ctx, String symbol, Instant ref) {
        Optional<Candle> bar = ctx.contextBar(symbol, Timeframe.H1, ref);
        OptionalDouble ema = ctx.contextInd(symbol, Timeframe.H1, IndicatorKind.EMA, EMA_PERIOD, ref);
        if (bar.isEmpty() || ema.isEmpty()) return 0;
        return Double.compare(bar.get().close(), ema.getAsDouble()) > 0 ? 1 : bar.get().close() < ema.getAsDouble() ? -1 : 0;
    }

    /** The gate for a direction at the context's time, with the reasons it checked. */
    public static boolean allows(StrategyContext ctx, Direction d, List<Reason> why) {
        Instant ref = referenceOpen(ctx.now());
        int s = d.sign();
        int usdx = state(ctx, USDX, ref);
        boolean dollar = usdx == -s; // long EURUSD needs a weak dollar (USDX down); short needs USDX up
        why.add(new Reason("USDX " + (s > 0 ? "below" : "above") + " EMA20(H1) on the bar opening " + ref, usdx, -s, dollar));
        int agree = 0;
        for (String peer : PEERS) {
            int st = state(ctx, peer, ref);
            if (st == s) agree++;
            why.add(new Reason(peer + (s > 0 ? " above" : " below") + " EMA20(H1)", st, s, st == s));
        }
        why.add(new Reason("peers agreeing (>= 2 of 3)", agree, MIN_AGREE, agree >= MIN_AGREE));
        return dollar && agree >= MIN_AGREE;
    }

    @Override
    public Optional<SignalProposal> onCandleClose(StrategyContext ctx) {
        Optional<SignalProposal> p = inner.onCandleClose(ctx);
        if (p.isEmpty()) return p;
        List<Reason> why = new ArrayList<>(p.get().reasons());
        if (!allows(ctx, p.get().direction(), why)) return Optional.empty();
        SignalProposal x = p.get();
        return Optional.of(new SignalProposal(x.direction(), x.stop(), x.targetR(), x.timeStopBars(), why, x.exitPlan(), x.invalidation(), x.memo()));
    }

    @Override
    public Optional<ExitDecision> onOpenSignalCandleClose(StrategyContext ctx, OpenTradeView open) {
        return inner.onOpenSignalCandleClose(ctx, open);
    }

    @Override
    public SignalProposal randomEntryPlan(StrategyContext ctx, Direction d, List<Reason> why) {
        if (!(inner instanceof Baseline b)) throw new IllegalStateException(inner.meta().id() + " has no borrowable exits");
        return b.randomEntryPlan(ctx, d, why);
    }
}
