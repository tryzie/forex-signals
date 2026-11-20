package dev.forexsignals.backtest;

import dev.forexsignals.signal.Signal;
import dev.forexsignals.stats.Bootstrap;
import dev.forexsignals.stats.Metrics;
import dev.forexsignals.stats.TradeR;
import dev.forexsignals.stats.ValidationGate;
import dev.forexsignals.stats.WalkForward;
import dev.forexsignals.strategy.ParamsHash;
import dev.forexsignals.strategy.api.Strategy;
import dev.forexsignals.strategy.impl.Confirmed;
import dev.forexsignals.strategy.impl.RandomControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs the pre-registered EURUSD confirmation hypothesis (docs/hyp/eurusd-confirmation.md) for one stage.
 *
 * Per pair (base B, confirmed C), seven engine runs on the stage window:
 *   B, B stress, B plain random control (B's rate);  C, C stress, C plain random control (C's rate),
 *   C confirmed random control = the confirmation gate applied to random entries at B's rate (§6.2).
 * Statistics use only signals raised inside the stage window. The development stage never loads holdout data (the
 * caller loads M1 only up to the development end).
 *
 * Verdict for a confirmed variant (§8): the existing gate checks, with every CI test (expectancy, beats plain control,
 * (a) beats base, (b) beats confirmed random control) judged on the Bonferroni-adjusted 95% interval for two hypotheses;
 * fewer than 200 trades is INCONCLUSIVE. Raw 90% intervals are reported alongside.
 */
public final class HypothesisRunner {

    private static final Logger log = LoggerFactory.getLogger(HypothesisRunner.class);
    public static final double RAW = 0.90, ADJUSTED = 0.95; // Bonferroni for 2 hypotheses: 1 - 0.10 / 2

    public record Stage(String name, Instant from, Instant to) {}

    public record Variant(String strategyId, String paramsHash, Validator.RunOutcome run, Validator.RunOutcome stress,
                          Validator.RunOutcome plainControl, double plainControlRate, ValidationGate.Result rawGate,
                          Bootstrap.Ci ciRaw, Bootstrap.Ci ciAdj, Bootstrap.Ci vsPlainRaw, Bootstrap.Ci vsPlainAdj) {}

    public record Confirmation(Variant confirmed, Variant base, Validator.RunOutcome confirmedRandom, double confirmedRandomRate,
                               Bootstrap.Ci vsBaseRaw, Bootstrap.Ci vsBaseAdj, Bootstrap.Ci vsConfRandomRaw, Bootstrap.Ci vsConfRandomAdj,
                               int baseTradesRemoved, double removedMeanR, double keptMeanR, String verdict, List<String> verdictNotes) {}

    private HypothesisRunner() {}

    /** Restrict a run's signals to the stage window [from, to). */
    static Validator.RunOutcome window(Validator.RunOutcome o, Stage st) {
        List<Signal> sig = o.signals().stream().filter(s -> !s.signalAt.isBefore(st.from()) && s.signalAt.isBefore(st.to())).toList();
        List<TradeR> tr = TradeR.of(sig);
        return new Validator.RunOutcome(o.strategyId(), o.role(), o.paramsHash(), sig, tr, Metrics.of(tr), o.rejections(), o.rejectionsBySymbol(),
                o.proposals(), o.eligibleBars(), o.warmupBlocked(), o.killSwitchAt(), sig.stream().filter(x -> x.flags.contains("AMBIGUOUS_BAR")).count());
    }

    static Variant variant(Validator.Inputs in, dev.forexsignals.store.MarketStore store, Strategy s, Stage st, Validator.RunOutcome run,
                           Validator.RunOutcome stress) {
        double rate = run.eligibleBars() == 0 ? 0 : (double) run.proposals() / run.eligibleBars();
        // Plain random control at this variant's own rate (same exits, no gate), as in Phase 2.
        Validator.RunOutcome ctrl = window(Validator.run(in, new RandomControl(s, rate), "CONTROL", in.costs()), st);
        Validator.RunOutcome w = window(run, st), ws = window(stress, st);
        Bootstrap.Ci ciRaw = Bootstrap.meanCi(w.trades(), 10_000, in.seed(), RAW), ciAdj = Bootstrap.meanCi(w.trades(), 10_000, in.seed(), ADJUSTED);
        Bootstrap.Ci vpRaw = Bootstrap.differenceCi(w.trades(), ctrl.trades(), 10_000, in.seed(), RAW);
        Bootstrap.Ci vpAdj = Bootstrap.differenceCi(w.trades(), ctrl.trades(), 10_000, in.seed(), ADJUSTED);
        Metrics repriced = Metrics.of(Validator.repriceStress(w.signals(), in.costs()));
        Metrics stressForGate = Double.isNaN(repriced.expectancy()) || ws.metrics().expectancy() <= repriced.expectancy() ? ws.metrics() : repriced;
        WalkForward.Result wf = WalkForward.run(w.trades(), st.from(), st.to());
        Validator.Regimes reg = Validator.regimes(store, in.feed(), s.meta().symbols(), w.trades(), st.from(), st.to());
        ValidationGate.Result gate = ValidationGate.evaluate(new ValidationGate.Inputs(w.metrics(), ciRaw, reg.covered(), reg.note(), wf, true, "",
                stressForGate, vpRaw));
        return new Variant(s.meta().id(), ParamsHash.of(s.meta()), w, ws, ctrl, rate, gate, ciRaw, ciAdj, vpRaw, vpAdj);
    }

    public static List<Confirmation> run(Validator.Inputs in, dev.forexsignals.store.MarketStore store, List<Strategy[]> pairs, Stage st) throws Exception {
        List<Confirmation> out = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (Strategy[] pair : pairs) {
                Strategy base = pair[0], conf = pair[1];
                Future<Validator.RunOutcome> bR = pool.submit(() -> Validator.run(in, base, "PARENT", in.costs()));
                Future<Validator.RunOutcome> bS = pool.submit(() -> Validator.run(in, base, "STRESS", in.costs().stressed()));
                Future<Validator.RunOutcome> cR = pool.submit(() -> Validator.run(in, conf, "PARENT", in.costs()));
                Future<Validator.RunOutcome> cS = pool.submit(() -> Validator.run(in, conf, "STRESS", in.costs().stressed()));
                Validator.RunOutcome baseRun = bR.get();
                double baseRate = baseRun.eligibleBars() == 0 ? 0 : (double) baseRun.proposals() / baseRun.eligibleBars();
                Confirmed confRandom = new Confirmed(conf.meta().name() + "__random_conf", new RandomControl(base, baseRate));
                Future<Validator.RunOutcome> crR = pool.submit(() -> Validator.run(in, confRandom, "CONFIRMED_CONTROL", in.costs()));

                Variant b = variant(in, store, base, st, baseRun, bS.get());
                Variant c = variant(in, store, conf, st, cR.get(), cS.get());
                Validator.RunOutcome cr = window(crR.get(), st);

                Bootstrap.Ci aRaw = Bootstrap.differenceCi(c.run().trades(), b.run().trades(), 10_000, in.seed(), RAW);
                Bootstrap.Ci aAdj = Bootstrap.differenceCi(c.run().trades(), b.run().trades(), 10_000, in.seed(), ADJUSTED);
                Bootstrap.Ci bRaw = Bootstrap.differenceCi(c.run().trades(), cr.trades(), 10_000, in.seed(), RAW);
                Bootstrap.Ci bAdj = Bootstrap.differenceCi(c.run().trades(), cr.trades(), 10_000, in.seed(), ADJUSTED);

                // Base trades the filter removed vs kept (matched on signal time and direction).
                Set<String> kept = new HashSet<>();
                for (Signal s : c.run().signals()) kept.add(s.signalAt + "|" + s.direction);
                double remSum = 0, keepSum = 0;
                int rem = 0, keep = 0;
                for (Signal s : b.run().signals()) {
                    if (s.state != Signal.State.CLOSED || s.flags.contains("END_OF_DATA")) continue;
                    if (kept.contains(s.signalAt + "|" + s.direction)) { keepSum += s.netR; keep++; } else { remSum += s.netR; rem++; }
                }

                List<String> notes = new ArrayList<>();
                String verdict = stage1Verdict(c, aAdj, bAdj, notes);
                out.add(new Confirmation(c, b, cr, baseRate, aRaw, aAdj, bRaw, bAdj, rem, rem == 0 ? Double.NaN : remSum / rem,
                        keep == 0 ? Double.NaN : keepSum / keep, verdict, notes));
                log.info("hypothesis {} {}: {} trades, net {}R, verdict {}", st.name(), c.strategyId(), c.run().metrics().trades(),
                        String.format("%.3f", c.run().metrics().expectancy()), verdict);
            }
        } finally {
            pool.shutdown();
        }
        return out;
    }

    /**
     * Development-stage verdict for a confirmed variant: every check below must pass. CI tests use the adjusted (95%)
     * interval. Fewer than 200 trades: INCONCLUSIVE whatever the point estimate.
     */
    static String stage1Verdict(Variant c, Bootstrap.Ci aAdj, Bootstrap.Ci bAdj, List<String> notes) {
        Metrics m = c.run().metrics();
        boolean fail = false, inconclusive = false;
        if (m.trades() < 200) { inconclusive = true; notes.add("only " + m.trades() + " trades (< 200): INCONCLUSIVE regardless of the estimate"); }
        if (!(m.expectancy() >= 0.10 && c.ciAdj().lo() > 0)) { fail = true; notes.add(String.format("expectancy %.3fR, adjusted 95%% CI low %.3f", m.expectancy(), c.ciAdj().lo())); }
        if (!(c.vsPlainAdj().lo() > 0)) { fail = true; notes.add(String.format("vs plain random control: adjusted CI low %.3f", c.vsPlainAdj().lo())); }
        if (!(aAdj.lo() > 0)) { fail = true; notes.add(String.format("(a) vs base without confirmation: adjusted CI low %.3f", aAdj.lo())); }
        if (!(bAdj.lo() > 0)) { fail = true; notes.add(String.format("(b) vs confirmed random control: adjusted CI low %.3f", bAdj.lo())); }
        for (ValidationGate.Check k : c.rawGate().checks()) {
            if (k.number() == 2 || k.number() == 8) continue; // judged above on the adjusted (95%) intervals
            if (k.status() == ValidationGate.Status.FAIL) { fail = true; notes.add("check " + k.number() + " " + k.name() + ": " + k.observed()); }
            if (k.status() == ValidationGate.Status.INCONCLUSIVE) { inconclusive = true; notes.add("check " + k.number() + " " + k.name() + " inconclusive: " + k.observed()); }
        }
        return fail ? "FAIL" : inconclusive ? "INCONCLUSIVE" : "STAGE1_PASS";
    }

    public static Map<String, Object> summary(List<Confirmation> cs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Confirmation c : cs) {
            m.put(c.confirmed().strategyId(), Map.of("trades", c.confirmed().run().metrics().trades(), "expectancy", c.confirmed().run().metrics().expectancy(),
                    "vsBase", c.vsBaseRaw().mean(), "vsConfirmedRandom", c.vsConfRandomRaw().mean(), "verdict", c.verdict()));
            m.put(c.base().strategyId(), Map.of("trades", c.base().run().metrics().trades(), "expectancy", c.base().run().metrics().expectancy()));
        }
        return m;
    }
}
