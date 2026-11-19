package dev.forexsignals.backtest;

import dev.forexsignals.engine.MarketPipeline;
import dev.forexsignals.market.Candle;
import dev.forexsignals.market.PriceBasis;
import dev.forexsignals.market.SymbolCatalog;
import dev.forexsignals.market.Tick;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.paper.CostModel;
import dev.forexsignals.risk.RejectionReason;
import dev.forexsignals.risk.RiskGate;
import dev.forexsignals.signal.Signal;
import dev.forexsignals.signal.TradeTracker;
import dev.forexsignals.strategy.ParamsHash;
import dev.forexsignals.strategy.StrategyRunner;
import dev.forexsignals.strategy.api.Direction;
import dev.forexsignals.strategy.api.ExitDecision;
import dev.forexsignals.strategy.api.OpenTradeView;
import dev.forexsignals.strategy.api.Strategy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * THE engine (CLAUDE.md rule 3): backtest, replay and live all build this class and feed it ticks and clock steps.
 * They differ only in the clock and the tick source.
 *
 * Per tick: (optional cost-stress widening of the spread) -> market pipeline (tick buffer, candles) -> candles that closed
 * are processed per close instant -> trade tracker on the tick -> book (equity, peak, day start, kill switch).
 * Per close instant: bars-since-entry and time stops for open trades -> strategy runner (exit management of open trades,
 * then proposals) -> each proposal priced at the alert (reference = latest tick at or before the close), checked by the
 * risk gate, and registered with the tracker only if approved.
 *
 * Phase 2: no strategy is live. {@link #STRATEGIES_LIVE} is a constant false; every approved signal is SHADOW (tracked,
 * never delivered). Switching a strategy to LIVE is a Phase 5 feature behind the validation gate.
 */
public final class Engine {

    public static final boolean STRATEGIES_LIVE = false;

    public record Settings(String feed, PriceBasis basis, Duration candleGrace, Duration latency, double startingBalance, long seed) {}

    private final Settings settings;
    private final MarketPipeline pipeline;
    private final StrategyRunner runner;
    private final List<Strategy> strategies;
    private final Map<String, Strategy> byId = new HashMap<>();
    private final Map<String, String> paramsHash = new HashMap<>();
    private final SymbolCatalog catalog;
    private final RiskGate gate;
    private final CostModel costs;
    private final TradeTracker tracker;
    private final Predicate<String> feedHealthy;
    private final double spreadStress;

    private final List<Candle> closedThisStep = new ArrayList<>();
    private final Map<String, Tick> lastTick = new HashMap<>();
    private final Map<String, Long> rejections = new LinkedHashMap<>();
    private final Map<String, Long> proposalsByStrategy = new LinkedHashMap<>();
    private double peak, dayStartEquity;
    private LocalDate tradingDay;
    private Instant killSwitchAt;
    private Tick latest;

    public Engine(Settings settings, List<Strategy> strategies, SymbolCatalog catalog, RiskGate gate, CostModel costs,
                  TradeTracker.M1Bars m1Bars, Predicate<String> feedHealthy) {
        this.settings = settings;
        this.strategies = List.copyOf(strategies);
        this.catalog = catalog;
        this.gate = gate;
        this.costs = costs;
        this.feedHealthy = feedHealthy;
        this.spreadStress = costs.settings().spreadMultiplier();
        for (Strategy s : strategies) {
            byId.put(s.meta().id(), s);
            paramsHash.put(s.meta().id(), ParamsHash.of(s.meta()));
            proposalsByStrategy.put(s.meta().id(), 0L);
        }
        this.runner = new StrategyRunner(strategies, settings.seed());
        this.tracker = new TradeTracker(costs, gateNews(gate), settings.latency(), m1Bars);
        this.pipeline = new MarketPipeline(settings.feed(), settings.basis(), settings.candleGrace(), closedThisStep::add, null, null, null);
        this.peak = settings.startingBalance();
        this.dayStartEquity = settings.startingBalance();
    }

    private static dev.forexsignals.risk.NewsCalendar gateNews(RiskGate g) { return g.news(); }

    // ------------------------------------------------------------------------------------ inputs

    public void onTime(Instant now) {
        pipeline.onTime(now);
        processCloses();
    }

    public void onTick(Tick raw) {
        // Cost stress on real bid/ask: widen the spread around the bid (modelled spreads are widened in the cost model).
        Tick t = raw;
        if (spreadStress != 1.0 && raw.hasAsk() && raw.hasBid()) {
            t = new Tick(raw.feed(), raw.symbol(), raw.ts(), raw.bid(), raw.bid() + (raw.ask() - raw.bid()) * spreadStress, raw.quote(), raw.recvTs(), raw.seq());
        }
        pipeline.onTick(t);
        processCloses();
        lastTick.put(t.symbol(), t);
        latest = t;
        tracker.onTick(t);
        book(t.ts());
    }

    /** End of input at end: incomplete bars are dropped; trades still open are closed at their last mark and flagged. */
    public void finish(Instant end) {
        pipeline.finishAt(end);
        processCloses();
        tracker.closeAllAtEnd(latest, end);
    }

    // ------------------------------------------------------------------------------------ candle closes

    private void processCloses() {
        if (closedThisStep.isEmpty()) return;
        TreeMap<Instant, List<Candle>> byTime = new TreeMap<>();
        for (Candle c : closedThisStep) byTime.computeIfAbsent(c.closeTime(), k -> new ArrayList<>()).add(c);
        closedThisStep.clear();
        for (var e : byTime.entrySet()) onCloseInstant(e.getKey(), e.getValue());
    }

    private void onCloseInstant(Instant at, List<Candle> batch) {
        // 1. Bars since entry and time stops for trades whose strategy's entry timeframe closed for their symbol.
        for (Signal s : List.copyOf(tracker.open())) {
            if (s.state != Signal.State.ACTIVE) continue;
            Strategy st = byId.get(s.strategyId);
            boolean entryClosed = batch.stream().anyMatch(c -> c.symbol().equals(s.symbol) && c.timeframe() == st.meta().entryTf());
            if (!entryClosed || !at.isAfter(s.filledAt)) continue;
            s.barsSinceEntry++;
            if (s.barsSinceEntry >= s.timeStopBars) tracker.requestExit(s, at, Signal.CloseReason.TIME_STOP, "time stop " + s.timeStopBars + " bars");
        }
        // 2. Strategies: exit management of active trades, then new proposals.
        StrategyRunner.Result r = runner.onCandlesClosed(at, batch, (id, sym) -> {
            Map<Object, OpenTradeView> views = new LinkedHashMap<>();
            for (Signal s : tracker.open()) {
                if (s.state != Signal.State.ACTIVE || !s.strategyId.equals(id) || !s.symbol.equals(sym) || s.exitRequestedAt != null) continue;
                views.put(s, new OpenTradeView(s.direction, s.signalAt, s.filledAt, s.reference, s.fill, s.initialStop, s.stop, s.rUnit,
                        s.barsSinceEntry, s.best, s.mfeR, s.memo));
            }
            return views;
        });
        for (StrategyRunner.Management m : r.management()) {
            Signal s = (Signal) m.tradeKey();
            if (m.decision().kind() == ExitDecision.Kind.MOVE_STOP) tracker.moveStop(s, m.decision().stopLevel());
            else tracker.requestExit(s, at, m.decision().rule().startsWith("invalidation") ? Signal.CloseReason.INVALIDATED : Signal.CloseReason.EXIT_RULE,
                    m.decision().rule());
        }
        for (StrategyRunner.Proposal p : r.proposals()) propose(p, at);
    }

    private void propose(StrategyRunner.Proposal p, Instant at) {
        String id = p.strategy().meta().id();
        proposalsByStrategy.merge(id, 1L, Long::sum);
        Tick ref = pipeline.buffer().latestAtOrBefore(p.symbol(), at).orElse(null);
        if (ref == null || !ref.hasBid() || !ref.hasAsk()) {
            rejections.merge(id + "|NO_QUOTE_AT_ALERT", 1L, Long::sum);
            return;
        }
        Direction d = p.proposal().direction();
        double reference = d == Direction.LONG ? ref.ask() : ref.bid();
        double point = Math.pow(10, -catalog.get(p.symbol()).digits());
        double dist = p.proposal().stop().distance(reference);
        double stop = Math.round((reference - d.sign() * dist) / point) * point;
        double r = Math.abs(reference - stop);
        // Round the target AWAY from the reference so the price-grid level is never below the planned R multiple
        // (rounding to nearest made 1.5R targets 1.496R and RR_TOO_LOW rejected them).
        double targetPoints = Math.ceil(p.proposal().targetR() * r / point - 1e-6);
        double target = reference + d.sign() * targetPoints * point;
        RiskGate.Candidate c = new RiskGate.Candidate(id, catalog.get(p.symbol()), d, at, ref.bid(), ref.ask(), stop, target,
                STRATEGIES_LIVE, feedHealthy.test(p.symbol()));
        RiskGate.Decision dec = gate.evaluate(c, book);
        if (!dec.isApproved()) {
            rejections.merge(id + "|" + dec.firstFailure(), 1L, Long::sum);
            return;
        }
        tracker.register(dec.approved(), p.strategy().meta().name(), p.strategy().meta().version(), paramsHash.get(id), p.proposal(), stop, target);
    }

    // ------------------------------------------------------------------------------------ book

    private final RiskGate.Book book = new RiskGate.Book() {
        @Override public double equity() { return Engine.this.equity(); }
        @Override public double dayStartEquity() { return dayStartEquity; }
        @Override public boolean killSwitchTripped() { return killSwitchAt != null; }
        @Override public List<RiskGate.Exposure> open() {
            List<RiskGate.Exposure> out = new ArrayList<>();
            for (Signal s : tracker.open()) out.add(new RiskGate.Exposure(catalog.get(s.symbol), s.direction));
            return out;
        }
    };

    public double equity() { return realized() + tracker.floatingUsd(lastTick::get); }

    private double realized() {
        double r = settings.startingBalance();
        for (Signal s : tracker.all()) if (s.state == Signal.State.CLOSED) r += s.pnlUsd;
        return r;
    }

    private void book(Instant now) {
        LocalDate day = TradeTracker.tradingDay(now);
        double eq = equity();
        if (!day.equals(tradingDay)) {
            tradingDay = day;
            dayStartEquity = eq;
        }
        peak = Math.max(peak, eq);
        if (killSwitchAt == null && eq <= peak * (1 - gate.config().killSwitchPct() / 100)) {
            killSwitchAt = now;
            for (Signal s : List.copyOf(tracker.open())) tracker.requestExit(s, now, Signal.CloseReason.KILL_SWITCH, "kill switch: equity -10% from peak");
        }
    }

    // ------------------------------------------------------------------------------------ results

    public List<Signal> signals() { return tracker.all(); }

    /** +1 if every open (active) trade on the symbol is long, -1 if all short, 0 if none or mixed. */
    public int netOpenDirection(String symbol) {
        int longs = 0, shorts = 0;
        for (Signal s : tracker.open()) {
            if (!s.symbol.equals(symbol) || s.state != Signal.State.ACTIVE) continue;
            if (s.direction == Direction.LONG) longs++; else shorts++;
        }
        return longs > 0 && shorts == 0 ? 1 : shorts > 0 && longs == 0 ? -1 : 0;
    }

    public Map<String, Long> rejections() { return rejections; }

    public Map<String, Long> proposals() { return proposalsByStrategy; }

    public Instant killSwitchAt() { return killSwitchAt; }

    public StrategyRunner runner() { return runner; }

    public double peakEquity() { return peak; }

    public static Map<RejectionReason, Long> byReason(Map<String, Long> rej, String strategyId) {
        Map<RejectionReason, Long> out = new EnumMap<>(RejectionReason.class);
        rej.forEach((k, v) -> {
            String[] p = k.split("\\|");
            if (p[0].equals(strategyId) && !p[1].equals("NO_QUOTE_AT_ALERT")) out.merge(RejectionReason.valueOf(p[1]), v, Long::sum);
        });
        return out;
    }
}
