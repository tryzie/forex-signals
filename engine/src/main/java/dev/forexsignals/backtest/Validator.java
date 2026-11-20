package dev.forexsignals.backtest;

import dev.forexsignals.indicator.Adx;
import dev.forexsignals.market.Candle;
import dev.forexsignals.market.PriceBasis;
import dev.forexsignals.market.SymbolCatalog;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.paper.CostModel;
import dev.forexsignals.paper.SpreadModel;
import dev.forexsignals.risk.NewsCalendar;
import dev.forexsignals.risk.RejectionReason;
import dev.forexsignals.risk.RiskGate;
import dev.forexsignals.signal.Signal;
import dev.forexsignals.stats.Bootstrap;
import dev.forexsignals.stats.Metrics;
import dev.forexsignals.stats.TradeR;
import dev.forexsignals.stats.ValidationGate;
import dev.forexsignals.stats.WalkForward;
import dev.forexsignals.store.MarketStore;
import dev.forexsignals.store.ResearchStore;
import dev.forexsignals.strategy.ParamsHash;
import dev.forexsignals.strategy.api.Strategy;
import dev.forexsignals.strategy.impl.RandomControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The Phase 2 validation pipeline for one strategy version (PLAN §8, §14.4):
 *  1. parent backtest on 3 years of M1 (costs on: commission, slippage incl. the measured fill bias, modelled spread)
 *  2. its random control at the parent's measured signal rate per eligible bar (same exits, sessions, risk gate)
 *  3. cost stress: spread x1.5, slippage x2
 *  4. metrics, week-block bootstrap, walk-forward (shown only if the params are frozen and unchanged), regimes, gate.
 * Every run stores the params hash per strategy. All three runs of a strategy run in parallel on shared read-only data.
 */
public final class Validator {

    private static final Logger log = LoggerFactory.getLogger(Validator.class);

    public record Inputs(String feed, Instant from, Instant to, Map<String, M1Series> m1, CostModel.Settings costs,
                         Map<String, dev.forexsignals.paper.InstrumentSpec> specs, SpreadModel spreads, NewsCalendar news,
                         SymbolCatalog catalog, RiskGate.Config risk, long seed, Duration latency) {}

    public record RunOutcome(String strategyId, String role, String paramsHash, List<Signal> signals, List<TradeR> trades, Metrics metrics,
                             Map<RejectionReason, Long> rejections, Map<String, Map<String, Long>> rejectionsBySymbol, long proposals, long eligibleBars, long warmupBlocked,
                             Instant killSwitchAt, long ambiguous) {}

    public record StrategyResult(String strategyId, String name, String version, String paramsHash, RunOutcome parent, RunOutcome control,
                                 RunOutcome stressed, Metrics repricedStress, double controlProbability, Bootstrap.Ci ci, Bootstrap.Ci vsControl,
                                 WalkForward.Result walkForward, Optional<String> oosWithheld, Instant visibleUntil, String regimeNote,
                                 boolean regimesCovered, ValidationGate.Result gate, UUID runId, boolean failedOnCosts) {}

    private Validator() {}

    /** One engine run of one strategy over the inputs. */
    public static RunOutcome run(Inputs in, Strategy s, String role, CostModel.Settings costSettings) {
        CostModel costs = new CostModel(costSettings, in.specs(), in.spreads());
        RiskGate gate = new RiskGate(in.risk(), costs, in.news());
        // Validation runs: synthetic M1 path ticks; the kill switch is recorded but does not halt (see Engine.Settings).
        Engine e = new Engine(new Engine.Settings(in.feed(), PriceBasis.BID, Duration.ofSeconds(2), in.latency(), 10_000, in.seed(), false, true, true),
                List.of(s), in.catalog(), gate, costs, (sym, t) -> BacktestRunner.at(in.m1(), sym, t), sym -> true);
        Map<String, M1Series> data = new TreeMap<>();
        for (String sym : s.meta().symbols()) if (in.m1().containsKey(sym)) data.put(sym, in.m1().get(sym));
        long t0 = System.nanoTime();
        BacktestRunner.run(e, data, in.spreads(), in.from(), in.to(), null);
        String id = s.meta().id();
        List<TradeR> trades = TradeR.of(e.signals());
        long amb = e.signals().stream().filter(x -> x.flags.contains("AMBIGUOUS_BAR")).count();
        log.info("run {} {}: {} signals, {} closed trades, net {}R/trade, in {} s", role, id, e.signals().size(), trades.size(),
                String.format("%.3f", Metrics.of(trades).expectancy()), (System.nanoTime() - t0) / 1_000_000_000);
        return new RunOutcome(id, role, ParamsHash.of(s.meta()), e.signals(), trades, Metrics.of(trades), Engine.byReason(e.rejections(), id), e.rejectionsBySymbol(),
                e.proposals().getOrDefault(id, 0L), e.runner().evaluated(id), e.runner().warmupBlocked(id), e.killSwitchAt(), amb);
    }

    public static StrategyResult validate(Inputs in, Strategy parent, ResearchStore research, MarketStore store) throws Exception {
        return validate(in, parent, research, store, true);
    }

    /**
     * recordVerdict = false for a DIAGNOSTIC run: the gate is computed for information only and NO validation_result row
     * is written, so a diagnostic can never become the PASS that a strategy's LIVE switch depends on.
     */
    public static StrategyResult validate(Inputs in, Strategy parent, ResearchStore research, MarketStore store, boolean recordVerdict) throws Exception {
        String id = parent.meta().id();
        String hash = ParamsHash.of(parent.meta());
        Instant startedAt = Instant.now();
        UUID runId = research == null ? UUID.randomUUID() : research.startRun("BACKTEST", in.feed(), in.from(), in.to(), "pepperstone_razor_ctrader", hash, null, startedAt);

        // Parent and stress run in parallel; the control needs the parent's measured rate.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RunOutcome> pf = pool.submit(() -> run(in, parent, "PARENT", in.costs()));
            Future<RunOutcome> sf = pool.submit(() -> run(in, parent, "STRESS", in.costs().stressed()));
            RunOutcome p = pf.get();
            double prob = p.eligibleBars() == 0 ? 0 : (double) p.proposals() / p.eligibleBars();
            RandomControl control = new RandomControl(parent, prob);
            RunOutcome c = run(in, control, "CONTROL", in.costs());
            RunOutcome st = sf.get();
            if (research != null) {
                research.runStrategy(runId, id, hash, "PARENT");
                research.runStrategy(runId, control.meta().id(), ParamsHash.of(control.meta()), "CONTROL");
            }

            Optional<String> withheld = research == null ? Optional.of("no database (test run)")
                    : ResearchStore.oosWithheldReason(research.latestFrozen(id), hash, startedAt);
            // Withheld: NOTHING after the first in-sample year is used or shown (metrics, CI, control, stress, trades, gate).
            Instant visibleUntil = withheld.isPresent() ? in.from().atZone(ZoneOffset.UTC).plusMonths(12).toInstant() : in.to();
            p = restrict(p, visibleUntil);
            c = restrict(c, visibleUntil);
            st = restrict(st, visibleUntil);
            Metrics repriced = Metrics.of(repriceStress(p.signals(), in.costs()));
            Bootstrap.Ci ci = Bootstrap.meanCi(p.trades(), 10_000, in.seed());
            Bootstrap.Ci vs = Bootstrap.differenceCi(p.trades(), c.trades(), 10_000, in.seed());
            WalkForward.Result wf = WalkForward.run(p.trades(), in.from(), in.to());
            var regimes = regimes(store, in.feed(), parent.meta().symbols(), p.trades());
            // Check 6 uses the WORSE of: the stressed re-run (its own trade set) and the parent's trades re-priced at stress.
            Metrics stressForGate = Double.isNaN(repriced.expectancy()) || st.metrics().expectancy() <= repriced.expectancy() ? st.metrics() : repriced;
            ValidationGate.Result gate = ValidationGate.evaluate(new ValidationGate.Inputs(p.metrics(), ci, regimes.covered(), regimes.note(), wf,
                    withheld.isEmpty(), withheld.orElse(""), stressForGate, vs));
            boolean failedOnCosts = p.metrics().trades() > 0 && p.metrics().grossExpectancy() > 0 && p.metrics().expectancy() <= 0;
            if (research != null && recordVerdict) {
                research.validationResult(parent.meta().name(), parent.meta().version(), hash, runId, gate.verdict().name(),
                        MarketStore.json(ValidationGate.asMap(gate)));
            }
            if (research != null) research.finishRun(runId, Instant.now());
            log.info("validation {}: {} (expectancy net {}R, gross {}R, {} trades)", id, gate.verdict(), String.format("%.3f", p.metrics().expectancy()),
                    String.format("%.3f", p.metrics().grossExpectancy()), p.metrics().trades());
            return new StrategyResult(id, parent.meta().name(), parent.meta().version(), hash, p, c, st, repriced, prob, ci, vs, wf, withheld,
                    visibleUntil, regimes.note(), regimes.covered(), gate, runId, failedOnCosts);
        } finally {
            pool.shutdown();
        }
    }

    /** Only signals raised before `until` (used when out-of-sample numbers must be withheld). */
    static RunOutcome restrict(RunOutcome o, Instant until) {
        if (!o.signals().isEmpty() && o.signals().stream().allMatch(s -> s.signalAt.isBefore(until))) return o;
        List<Signal> sig = o.signals().stream().filter(s -> s.signalAt.isBefore(until)).toList();
        List<TradeR> tr = TradeR.of(sig);
        return new RunOutcome(o.strategyId(), o.role(), o.paramsHash(), sig, tr, Metrics.of(tr), o.rejections(), o.rejectionsBySymbol(), o.proposals(), o.eligibleBars(),
                o.warmupBlocked(), o.killSwitchAt() != null && o.killSwitchAt().isBefore(until) ? o.killSwitchAt() : null,
                sig.stream().filter(x -> x.flags.contains("AMBIGUOUS_BAR")).count());
    }

    /**
     * The parent's OWN trades re-priced at cost stress (spread x1.5, slippage x2): an extra half spread at entry (and at
     * exit for shorts, which close on the ask) plus the entry and exit slippage once more. Unlike the stressed re-run,
     * the trade set does not change, so cheap survivors cannot flatter the stress check.
     */
    static List<TradeR> repriceStress(List<Signal> signals, CostModel.Settings base) {
        double extraSlipFactor = base.stressed().slippageMultiplier() / base.slippageMultiplier() - 1;
        double extraSpreadFactor = base.stressed().spreadMultiplier() / base.spreadMultiplier() - 1;
        List<TradeR> repriced = new ArrayList<>();
        for (Signal s : signals) {
            if (s.state != Signal.State.CLOSED || s.flags.contains("END_OF_DATA") || Double.isNaN(s.entrySpread)) continue;
            double spreadSides = s.direction == dev.forexsignals.strategy.api.Direction.SHORT ? 2 : 1;
            double extra = extraSpreadFactor * s.entrySpread * spreadSides + extraSlipFactor * (s.entrySlip + s.exitSlip);
            repriced.add(new TradeR(s.symbol, s.filledAt, s.closedAt, s.grossR, s.netR - extra / s.rUnit, s.worstCaseNetR, s.closeReason.name()));
        }
        repriced.sort((a, b) -> a.exit().compareTo(b.exit()));
        return repriced;
    }

    public record Regimes(boolean covered, String note) {}

    /**
     * PLAN §8 check 1: a trending and a ranging period. For each calendar month the mean H4 ADX14 over the strategy's
     * symbols; months in the top third are "trending", bottom third "ranging". Covered if trades fall in both kinds.
     */
    static Regimes regimes(MarketStore store, String feed, List<String> symbols, List<TradeR> trades) {
        if (store == null || trades.isEmpty()) return new Regimes(false, "no data");
        Map<YearMonth, double[]> sum = new TreeMap<>();
        for (String sym : symbols) {
            Adx adx = new Adx(14);
            for (Candle c : store.loadDerived(feed, sym, Timeframe.H4, Instant.EPOCH, Instant.parse("9999-01-01T00:00:00Z"))) {
                adx.update(c);
                if (!adx.ready()) continue;
                double[] v = sum.computeIfAbsent(YearMonth.from(c.openTime().atZone(ZoneOffset.UTC)), k -> new double[2]);
                v[0] += adx.value();
                v[1]++;
            }
        }
        if (sum.size() < 3) return new Regimes(false, "fewer than 3 months of H4 data");
        List<Map.Entry<YearMonth, Double>> months = new ArrayList<>();
        sum.forEach((k, v) -> months.add(Map.entry(k, v[0] / v[1])));
        List<Double> sorted = months.stream().map(Map.Entry::getValue).sorted().toList();
        double lo = sorted.get(sorted.size() / 3), hi = sorted.get(sorted.size() * 2 / 3);
        Set<YearMonth> trending = new TreeSet<>(), ranging = new TreeSet<>();
        for (var e : months) { if (e.getValue() >= hi) trending.add(e.getKey()); else if (e.getValue() <= lo) ranging.add(e.getKey()); }
        Set<YearMonth> traded = new TreeSet<>();
        for (TradeR t : trades) traded.add(YearMonth.from(t.entry().atZone(ZoneOffset.UTC)));
        long tr = traded.stream().filter(trending::contains).count(), rg = traded.stream().filter(ranging::contains).count();
        return new Regimes(tr > 0 && rg > 0, String.format("traded in %d trending and %d ranging months (H4 ADX14 terciles: <= %.1f ranging, >= %.1f trending)", tr, rg, lo, hi));
    }

    static <T> List<T> unmodifiable(List<T> l) { return Collections.unmodifiableList(l); }

    static Map<String, Object> mapOf() { return new HashMap<>(); }
}
