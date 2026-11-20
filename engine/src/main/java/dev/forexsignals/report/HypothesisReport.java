package dev.forexsignals.report;

import dev.forexsignals.backtest.HypothesisRunner;
import dev.forexsignals.history.UsdxCheck;
import dev.forexsignals.indicator.Ema;
import dev.forexsignals.market.Candle;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.signal.Signal;
import dev.forexsignals.stats.Bootstrap;
import dev.forexsignals.stats.Metrics;
import dev.forexsignals.stats.ValidationGate;
import dev.forexsignals.store.MarketStore;
import dev.forexsignals.strategy.impl.Confirmed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Markdown report of one stage of the pre-registered EURUSD confirmation hypothesis. Always contains all four variants.
 * The trade count is the first column of every table. Raw 90% and Bonferroni-adjusted 95% intervals are shown side by side.
 */
public final class HypothesisReport {

    public record Context(Instant at, HypothesisRunner.Stage stage, Map<String, String> frozenHashes, String costsNote) {}

    private HypothesisReport() {}

    static String ci(Bootstrap.Ci c) { return Double.isNaN(c.lo()) ? "n/a" : String.format("%.3f .. %.3f", c.lo(), c.hi()); }

    static String r(double v) { return Double.isNaN(v) ? "n/a" : String.format("%+.3f", v); }

    static String row(String verdict, HypothesisRunner.Variant v) {
        Metrics m = v.run().metrics(), s = v.stress().metrics();
        return String.format("| %s | **%d** | %s | %s | %.3f | %s | %s | %.2f | %.1f | %s | %s | %s | %s | %s |%n", v.strategyId(), m.trades(), r(m.expectancy()),
                r(m.grossExpectancy()), m.avgCostR(), ci(v.ciRaw()), ci(v.ciAdj()), m.profitFactor(), m.maxDrawdownR(), r(s.expectancy()),
                r(v.vsPlainRaw().mean()), ci(v.vsPlainRaw()), ci(v.vsPlainAdj()), verdict);
    }

    /**
     * USDX_SYN descriptive cross-check (never a gate input): on each kept confirmed signal's reference bar, does the ICE
     * synthetic agree with the broker USDX on "close vs EMA20(H1)"? Uses only H1 bars inside [from - 10 days, to).
     */
    static String synCrossCheck(MarketStore store, String feed, HypothesisRunner.Stage st, List<Signal> kept) {
        Instant from = st.from().minus(Duration.ofDays(10)), to = st.to();
        Map<String, Map<Instant, Double>> h1 = new HashMap<>();
        for (String s : new String[]{"USDX", "EURUSD", "USDJPY", "GBPUSD", "USDCAD", "USDSEK", "USDCHF"}) {
            Map<Instant, Double> m = new TreeMap<>();
            for (Candle c : store.loadDerived(feed, s, Timeframe.H1, from, to)) m.put(c.openTime(), c.close());
            h1.put(s, m);
        }
        String[] syn = {"EURUSD", "USDJPY", "GBPUSD", "USDCAD", "USDSEK", "USDCHF"};
        Map<Instant, Integer> synState = new HashMap<>(), usdxState = new HashMap<>();
        Ema es = new Ema(20), eu = new Ema(20);
        for (Instant t : h1.get("EURUSD").keySet()) {
            double[] px = new double[syn.length];
            boolean ok = true;
            for (int i = 0; i < syn.length && ok; i++) { Double v = h1.get(syn[i]).get(t); if (v == null) ok = false; else px[i] = v; }
            if (!ok) continue;
            double v = UsdxCheck.synthetic(px);
            es.add(v);
            if (es.ready()) synState.put(t, Double.compare(v, es.value()));
        }
        for (var e : h1.get("USDX").entrySet()) {
            eu.add(e.getValue());
            if (eu.ready()) usdxState.put(e.getKey(), Double.compare(e.getValue(), eu.value()));
        }
        int both = 0, agree = 0, missing = 0;
        for (Signal s : kept) {
            Instant ref = Confirmed.referenceOpen(s.signalAt);
            Integer a = usdxState.get(ref), b = synState.get(ref);
            if (a == null || b == null) { missing++; continue; }
            both++;
            if (a.equals(b)) agree++;
        }
        return String.format("%d of %d kept signals (%.1f%%) had USDX_SYN on the same side of its EMA20(H1) as the broker USDX; %d without a "
                + "comparable bar. Descriptive only: USDX_SYN is never a gate input.", agree, both, both == 0 ? 0 : 100.0 * agree / both, missing);
    }

    public static Path write(Path dir, Context ctx, List<HypothesisRunner.Confirmation> cs, MarketStore store, String feed) throws IOException {
        Files.createDirectories(dir);
        HypothesisRunner.Stage st = ctx.stage();
        StringBuilder md = new StringBuilder();
        md.append("# EURUSD cross-market confirmation: ").append(st.name()).append(" stage\n\n");
        md.append("Pre-registration: `docs/hyp/eurusd-confirmation.md` (approved and frozen 2026-11-20). Run at ").append(ctx.at()).append(" UTC.\n\n");
        md.append("Window: **").append(st.from()).append(" to ").append(st.to()).append("** (signals raised in [from, to)).\n\n");
        if (st.name().equals("DEVELOPMENT")) {
            md.append("> **Development stage only.** No holdout data was loaded. The holdout runs exactly once, after the owner has seen this result.\n\n");
        }
        md.append("Costs: ").append(ctx.costsNote()).append("\n\n");

        md.append("## Frozen parameter hashes\n\n| Variant | Params hash (frozen) | Run hash |\n|---|---|---|\n");
        for (var c : cs) {
            for (var v : List.of(c.base(), c.confirmed())) {
                md.append("| ").append(v.strategyId()).append(" | `").append(ctx.frozenHashes().get(v.strategyId())).append("` | `").append(v.paramsHash()).append("` |\n");
            }
        }

        md.append("\n## All four variants\n\nTrades are shown first: below 200 trades a confirmed variant is INCONCLUSIVE, whatever its estimate. ");
        md.append("Net R per trade after all costs. CIs: 10,000 week-block bootstrap resamples; raw = 90%, adjusted = Bonferroni for two ");
        md.append("hypotheses (95%). **A pass needs the adjusted lower bounds > 0.**\n\n");
        md.append("| Variant | Trades | Net R | Gross R | Cost R | Raw 90% CI | Adj. 95% CI | PF | Max DD (R) | Stress net R | vs plain control | raw CI | adj. CI | Verdict |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (var c : cs) {
            md.append(row("comparison only (base)", c.base()));
            md.append(row("**" + c.verdict() + "**", c.confirmed()));
        }

        md.append("\n## Tests (a) and (b)\n\n| Confirmed variant | Trades | (a) vs base: diff | raw 90% CI | adj. 95% CI | (b) vs confirmed random control: diff | raw 90% CI | adj. 95% CI |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        for (var c : cs) {
            md.append(String.format("| %s | **%d** | %s | %s | %s | %s | %s | %s |%n", c.confirmed().strategyId(), c.confirmed().run().metrics().trades(),
                    r(c.vsBaseRaw().mean()), ci(c.vsBaseRaw()), ci(c.vsBaseAdj()), r(c.vsConfRandomRaw().mean()), ci(c.vsConfRandomRaw()), ci(c.vsConfRandomAdj())));
        }

        md.append("\n## What the filter removed\n\nBase trades matched to the confirmed variant on signal time and direction.\n\n");
        md.append("| Pair | Base trades kept | Kept mean R | Base trades removed | Removed mean R | Base total R | Confirmed total R |\n|---|---|---|---|---|---|---|\n");
        for (var c : cs) {
            int kept = c.base().run().metrics().trades() - c.baseTradesRemoved();
            md.append(String.format("| %s / %s | %d | %s | %d | %s | %.1f | %.1f |%n", c.base().strategyId(), c.confirmed().strategyId(), kept, r(c.keptMeanR()),
                    c.baseTradesRemoved(), r(c.removedMeanR()), c.base().run().metrics().totalR(), c.confirmed().run().metrics().totalR()));
        }
        md.append("\nNote: a confirmed trade can differ from its base twin when the risk gate state differs (e.g. max open signals), so kept + removed ");
        md.append("need not equal the confirmed count exactly.\n");

        md.append("\n## Controls\n\n| Control | Rate per eligible bar | Trades | Net R |\n|---|---|---|---|\n");
        for (var c : cs) {
            for (var v : List.of(c.base(), c.confirmed())) {
                md.append(String.format("| plain random control of %s | %.5f | %d | %s |%n", v.strategyId(), v.plainControlRate(), v.plainControl().metrics().trades(),
                        r(v.plainControl().metrics().expectancy())));
            }
            md.append(String.format("| confirmed random control of %s (base rate, then the gate) | %.5f | %d | %s |%n", c.confirmed().strategyId(), c.confirmedRandomRate(),
                    c.confirmedRandom().metrics().trades(), r(c.confirmedRandom().metrics().expectancy())));
        }

        md.append("\n## Verdict details (confirmed variants)\n\n");
        for (var c : cs) {
            md.append("### ").append(c.confirmed().strategyId()).append(": ").append(c.verdict()).append("\n\n");
            md.append("Validation-gate checks (checks 2 and 8 are judged on the adjusted intervals above; raw shown here):\n\n| # | Check | Status | Observed | Rule |\n|---|---|---|---|---|\n");
            for (ValidationGate.Check k : c.confirmed().rawGate().checks()) {
                md.append("| ").append(k.number()).append(" | ").append(k.name()).append(" | ").append(k.status()).append(" | ")
                        .append(k.observed().replace("|", "/")).append(" | ").append(k.rule()).append(" |\n");
            }
            md.append("\nWhy not a pass:\n\n");
            if (c.verdictNotes().isEmpty()) md.append("- (all checks passed)\n");
            c.verdictNotes().forEach(n -> md.append("- ").append(n).append('\n'));
            md.append("\nRejections by the risk gate: ").append(c.confirmed().run().rejections()).append("\n\n");
        }

        md.append("## USDX_SYN cross-check (descriptive, never in the gate)\n\n");
        for (var c : cs) md.append("- ").append(c.confirmed().strategyId()).append(": ").append(synCrossCheck(store, feed, st, c.confirmed().run().signals())).append('\n');

        md.append("\n## Disclosures\n\n");
        md.append("- The base entry rules were tested in Phase 2 on all three years, so no window, including the holdout, is fresh for them; ");
        md.append("the confirmation gate and the split were fixed before any run.\n");
        md.append("- Two confirmed variants: CI tests use Bonferroni-adjusted 95% intervals; raw 90% shown for information.\n");
        md.append("- Verdicts here are Stage 1 (development) only. A final PASS needs Stage 2 (holdout, run once) as well; a pass would allow SHADOW tracking only.\n");

        String stamp = ctx.at().toString().replace(':', '-');
        Path out = dir.resolve("hypothesis-eurusd-confirmation-" + st.name().toLowerCase() + "-" + stamp + ".md");
        Files.writeString(out, md);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("stage", st.name());
        json.put("from", st.from().toString());
        json.put("to", st.to().toString());
        json.put("results", HypothesisRunner.summary(cs));
        Files.writeString(dir.resolve("hypothesis-eurusd-confirmation-" + st.name().toLowerCase() + "-" + stamp + ".json"), MarketStore.json(json));
        return out;
    }
}
