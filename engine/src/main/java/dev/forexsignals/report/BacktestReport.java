package dev.forexsignals.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.forexsignals.backtest.FillBias;
import dev.forexsignals.backtest.Validator;
import dev.forexsignals.paper.CostModel;
import dev.forexsignals.paper.SpreadModel;
import dev.forexsignals.risk.NewsCalendar;
import dev.forexsignals.signal.Signal;
import dev.forexsignals.stats.Metrics;
import dev.forexsignals.stats.ValidationGate;
import dev.forexsignals.stats.WalkForward;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Phase 2 backtest/validation report, JSON (everything, incl. every trade) and HTML (for reading). Every report carries
 * the fill-model note, the spread-model note and the news-coverage note, prints the sampled spread weeks and medians and
 * the measured fill bias, shows gross R next to net R, rejections by reason, and WITHHOLDS out-of-sample numbers when the
 * parameters were not frozen or changed after the freeze.
 */
public final class BacktestReport {

    public static final String FILL_NOTE = "Fill model (M1 backtest): a signal is raised at the close of its candle; the fill is the open of the first "
            + "M1 bar at or after signal + 10 s manual-entry latency, at the modelled ask (long) or bid (short), plus slippage. The signal "
            + "candle's close is never used. Each M1 bar is replayed as open, adverse extreme, favourable extreme, close; if stop and target "
            + "fall inside the same M1 bar the stop wins and the trade is flagged AMBIGUOUS_BAR. The bias of this model against real "
            + "cTrader ticks was measured on the sampled weeks and, where real fills were worse on average, added as extra slippage "
            + "(table below). The 'worst case within the minute' column fills instead at the adverse extreme of the minute containing "
            + "signal + latency.";
    public static final String SPREAD_NOTE = "The spread in M1 backtests is MODELLED: the median spread per session bucket of one sampled cTrader "
            + "tick-history week per quarter (table below). It is an approximation of the real spread at each moment; the cost-stress "
            + "check widens it by 50% (and doubles slippage).";
    public static final String NEWS_NOTE = "News filter: ONLY MAJOR events are filtered (FOMC, NFP, CPI, ECB, BoE, BoJ and RBA rate decisions; "
            + "config/news.csv, built from official publishers; BoJ times are approximate). Standard high-impact events (retail sales, "
            + "PMIs, GDP and similar) are NOT filtered.";

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    public record Context(Instant generatedAt, String feed, Instant from, Instant to, CostModel.Settings costs, SpreadModel spreads,
                          NewsCalendar news, Map<String, FillBias.SymbolBias> fillBias, List<String> weeks) {}

    private BacktestReport() {}

    public static Map<String, Object> json(Context ctx, List<Validator.StrategyResult> results) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", ctx.generatedAt().toString());
        root.put("feed", ctx.feed());
        root.put("data", Map.of("from", ctx.from().toString(), "to", ctx.to().toString()));
        root.put("notes", List.of(FILL_NOTE, SPREAD_NOTE, NEWS_NOTE, "No strategy is enabled for live signals (Phase 2)."));
        root.put("costs", ctx.costs());
        root.put("spreadSampleWeeks", ctx.weeks());
        root.put("spreadMedians", ctx.spreads().samples());
        root.put("fillBias", ctx.fillBias());
        root.put("newsCoverage", Map.of("from", ctx.news().coverFrom().toString(), "to", ctx.news().coverTo().toString(), "events", ctx.news().events().size()));
        List<Map<String, Object>> strategies = new ArrayList<>();
        for (Validator.StrategyResult r : results) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("strategy", r.strategyId());
            s.put("paramsHash", r.paramsHash());
            s.put("verdict", r.gate().verdict().name());
            s.put("checks", r.gate().checks());
            s.put("failedOnCosts", r.failedOnCosts());
            s.put("parent", outcome(r.parent()));
            s.put("control", outcome(r.control()));
            s.put("controlProbabilityPerEligibleBar", r.controlProbability());
            s.put("stressed", outcome(r.stressed()));
            s.put("parentTradesRepricedAtStress", r.repricedStress());
            s.put("visibleUntil", r.visibleUntil().toString());
            s.put("expectancyCi90", r.ci());
            s.put("vsControlCi90", r.vsControl());
            s.put("regimes", r.regimeNote());
            if (r.oosWithheld().isPresent()) s.put("walkForward", Map.of("WITHHELD", r.oosWithheld().get()));
            else s.put("walkForward", r.walkForward());
            s.put("trades", trades(r.parent().signals()));
            strategies.add(s);
        }
        root.put("strategies", strategies);
        return root;
    }

    private static Map<String, Object> outcome(Validator.RunOutcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strategy", o.strategyId());
        m.put("role", o.role());
        m.put("paramsHash", o.paramsHash());
        m.put("metrics", o.metrics());
        m.put("signals", o.signals().size());
        m.put("proposals", o.proposals());
        m.put("eligibleBars", o.eligibleBars());
        m.put("warmupBlocked", o.warmupBlocked());
        m.put("rejectionsByReason", o.rejections());
        m.put("rejectionsBySymbol", o.rejectionsBySymbol());
        m.put("killSwitchTrippedAt", o.killSwitchAt() == null ? null : o.killSwitchAt().toString());
        m.put("ambiguousBars", o.ambiguous());
        Map<String, Long> states = new TreeMap<>();
        for (Signal s : o.signals()) states.merge(s.state + (s.closeReason == null ? "" : ":" + s.closeReason), 1L, Long::sum);
        m.put("outcomes", states);
        return m;
    }

    private static List<Map<String, Object>> trades(List<Signal> signals) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Signal s : signals) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", s.symbol);
            m.put("direction", s.direction.name());
            m.put("signalAt", s.signalAt.toString());
            m.put("state", s.state.name());
            m.put("closeReason", s.closeReason == null ? null : s.closeReason.name());
            m.put("reference", s.reference);
            m.put("stop", s.initialStop);
            m.put("target", s.target);
            m.put("lots", s.lots);
            m.put("fill", Double.isNaN(s.fill) ? null : s.fill);
            m.put("filledAt", s.filledAt == null ? null : s.filledAt.toString());
            m.put("close", Double.isNaN(s.close) ? null : s.close);
            m.put("closedAt", s.closedAt == null ? null : s.closedAt.toString());
            m.put("grossR", Double.isNaN(s.grossR) ? null : s.grossR);
            m.put("netR", Double.isNaN(s.netR) ? null : s.netR);
            m.put("worstCaseNetR", Double.isNaN(s.worstCaseNetR) ? null : s.worstCaseNetR);
            m.put("estCostR", s.estCostR);
            m.put("pnlUsd", Double.isNaN(s.pnlUsd) ? null : s.pnlUsd);
            m.put("flags", s.flags);
            m.put("reasons", s.reasons);
            out.add(m);
        }
        return out;
    }

    public static Path write(Path dir, Context ctx, List<Validator.StrategyResult> results) throws IOException {
        Files.createDirectories(dir);
        String stem = "validation-" + ctx.feed() + "-" + ctx.generatedAt().toString().replace(':', '-');
        JSON.writeValue(dir.resolve(stem + ".json").toFile(), json(ctx, results));
        Path html = dir.resolve(stem + ".html");
        Files.writeString(html, html(ctx, results));
        return html;
    }

    private static String esc(Object o) {
        return String.valueOf(o).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String f(double v) { return Double.isNaN(v) ? "-" : Double.isInfinite(v) ? "inf" : String.format("%.3f", v); }

    static String html(Context ctx, List<Validator.StrategyResult> results) {
        StringBuilder h = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>Phase 2 validation</title><style>"
                + "body{font-family:system-ui,sans-serif;margin:24px;max-width:1200px}table{border-collapse:collapse;margin:8px 0 16px}"
                + "td,th{border:1px solid #bbb;padding:3px 8px;font-size:13px;text-align:right}th{background:#eee}td:first-child{text-align:left}"
                + ".PASS{color:#06702b;font-weight:700}.FAIL{color:#b00020;font-weight:700}.INCONCLUSIVE{color:#8a5a00;font-weight:700}"
                + ".note{background:#f6f6f6;border-left:4px solid #888;padding:6px 10px;margin:8px 0}</style></head><body>");
        h.append("<h1>Phase 2 validation report</h1><p>Feed <b>").append(esc(ctx.feed())).append("</b>, data ").append(ctx.from()).append(" to ")
                .append(ctx.to()).append(", generated ").append(ctx.generatedAt()).append(" UTC. All results are after costs unless marked gross. "
                        + "<b>No strategy is enabled for live signals.</b></p>");
        for (String n : List.of(FILL_NOTE, SPREAD_NOTE, NEWS_NOTE)) h.append("<div class=\"note\">").append(esc(n)).append("</div>");

        h.append("<h2>Verdicts</h2><table><tr><th>Strategy</th><th>Verdict</th><th>Trades</th><th>Net R/trade</th><th>Gross R/trade</th>"
                + "<th>Avg cost R</th><th>90% CI</th><th>PF</th><th>Max DD R</th><th>Stress net R</th><th>Control net R</th><th>Failed on costs</th></tr>");
        for (var r : results) {
            Metrics m = r.parent().metrics();
            h.append("<tr><td>").append(esc(r.strategyId())).append("</td><td class=\"").append(r.gate().verdict()).append("\">").append(r.gate().verdict())
                    .append("</td><td>").append(m.trades()).append("</td><td>").append(f(m.expectancy())).append("</td><td>").append(f(m.grossExpectancy()))
                    .append("</td><td>").append(f(m.avgCostR())).append("</td><td>").append(f(r.ci().lo())).append(" .. ").append(f(r.ci().hi()))
                    .append("</td><td>").append(f(m.profitFactor())).append("</td><td>").append(f(m.maxDrawdownR())).append("</td><td>")
                    .append(f(r.stressed().metrics().expectancy())).append("</td><td>").append(f(r.control().metrics().expectancy())).append("</td><td>")
                    .append(r.failedOnCosts() ? "yes" : "no").append("</td></tr>");
        }
        h.append("</table>");

        for (var r : results) {
            h.append("<h2>").append(esc(r.strategyId())).append(" <span class=\"").append(r.gate().verdict()).append("\">").append(r.gate().verdict())
                    .append("</span></h2><p>params hash <code>").append(r.paramsHash()).append("</code></p>");
            if (r.oosWithheld().isPresent()) {
                h.append("<p class=\"INCONCLUSIVE\">OUT-OF-SAMPLE NUMBERS WITHHELD (").append(esc(r.oosWithheld().get()))
                        .append("). Every number and trade below covers ONLY signals before ").append(r.visibleUntil()).append(".</p>");
            }
            h.append("<table><tr><th>#</th><th>Check</th><th>Status</th><th>Observed</th><th>Rule</th></tr>");
            for (ValidationGate.Check c : r.gate().checks()) {
                h.append("<tr><td>").append(c.number()).append("</td><td>").append(esc(c.name())).append("</td><td class=\"").append(c.status()).append("\">")
                        .append(c.status()).append("</td><td>").append(esc(c.observed())).append("</td><td>").append(esc(c.rule())).append("</td></tr>");
            }
            h.append("</table>");
            h.append("<table><tr><th>Run</th><th>Signals</th><th>Trades</th><th>Net R</th><th>Gross R</th><th>Win %</th><th>Worst-case-fill net R</th>"
                    + "<th>Ambiguous bars</th><th>Kill switch</th><th>Warm-up skipped</th></tr>");
            for (var o : List.of(r.parent(), r.control(), r.stressed())) {
                Metrics m = o.metrics();
                h.append("<tr><td>").append(o.role()).append(" ").append(esc(o.strategyId())).append("</td><td>").append(o.signals().size()).append("</td><td>")
                        .append(m.trades()).append("</td><td>").append(f(m.expectancy())).append("</td><td>").append(f(m.grossExpectancy())).append("</td><td>")
                        .append(f(100 * m.winRate())).append("</td><td>").append(f(m.worstCaseFillExpectancy())).append("</td><td>").append(o.ambiguous())
                        .append(m.trades() == 0 ? "" : String.format(" (%.1f%%)", 100.0 * o.ambiguous() / m.trades())).append("</td><td>")
                        .append(o.killSwitchAt() == null ? "not tripped" : "tripped " + o.killSwitchAt()).append("</td><td>").append(o.warmupBlocked()).append("</td></tr>");
            }
            h.append("</table><p>Cost stress, the parent's own trades re-priced (spread x1.5, slippage x2): net ")
                    .append(f(r.repricedStress().expectancy())).append("R/trade; the stressed re-run (its own trade set): ")
                    .append(f(r.stressed().metrics().expectancy())).append("R/trade. Check 6 uses the worse of the two.</p>");
            h.append("<p>Control fires with p = ").append(String.format("%.5f", r.controlProbability())).append(" per eligible bar (parent: ")
                    .append(r.parent().proposals()).append(" proposals / ").append(r.parent().eligibleBars()).append(" eligible bars). Strategy minus control: ")
                    .append(f(r.vsControl().mean())).append("R, 90% CI ").append(f(r.vsControl().lo())).append(" .. ").append(f(r.vsControl().hi())).append(".</p>");
            h.append("<p>Rejections by reason (parent): ").append(esc(r.parent().rejections())).append("</p>");
            h.append("<p>Rejections by symbol (parent): ").append(esc(r.parent().rejectionsBySymbol())).append("</p>");
            h.append("<p>Regimes: ").append(esc(r.regimeNote())).append("</p>");
            if (r.oosWithheld().isPresent()) {
                h.append("<p class=\"INCONCLUSIVE\">Walk-forward: OUT-OF-SAMPLE NUMBERS WITHHELD: ").append(esc(r.oosWithheld().get())).append("</p>");
            } else {
                WalkForward.Result w = r.walkForward();
                h.append("<table><tr><th>IS from</th><th>OOS from</th><th>OOS to</th><th>IS trades</th><th>IS R</th><th>OOS trades</th><th>OOS R</th></tr>");
                for (var x : w.windows()) {
                    h.append("<tr><td>").append(x.isFrom()).append("</td><td>").append(x.oosFrom()).append("</td><td>").append(x.oosTo()).append("</td><td>")
                            .append(x.isTrades()).append("</td><td>").append(f(x.isExpectancy())).append("</td><td>").append(x.oosTrades()).append("</td><td>")
                            .append(f(x.oosExpectancy())).append("</td></tr>");
                }
                h.append("</table><p>Pooled OOS ").append(f(w.oosExpectancy())).append("R over ").append(w.oosTrades()).append(" trades vs IS ")
                        .append(f(w.isExpectancy())).append("R: ").append(w.verdict()).append("</p>");
            }
            h.append("<p>Positive-R share by symbol: ").append(esc(r.parent().metrics().positiveShareBySymbol())).append("</p>");
        }

        h.append("<h2>Inputs</h2><h3>Fill-model bias (real cTrader ticks vs M1 model, sampled weeks)</h3><table><tr><th>Symbol</th><th>Samples</th>"
                + "<th>Long adverse</th><th>Short adverse</th><th>Mean adverse</th><th>Extra slippage added</th></tr>");
        ctx.fillBias().values().forEach(b -> h.append("<tr><td>").append(b.symbol()).append("</td><td>").append(b.samples()).append("</td><td>")
                .append(String.format("%.6f", b.longAdverse())).append("</td><td>").append(String.format("%.6f", b.shortAdverse())).append("</td><td>")
                .append(String.format("%.6f", b.meanAdverse())).append("</td><td>").append(String.format("%.6f", b.extraSlippage())).append("</td></tr>"));
        h.append("</table><h3>Spread model: sampled weeks and medians (price units)</h3><p>Weeks: ").append(esc(ctx.weeks())).append("</p>");
        Map<String, Map<String, String>> grid = new TreeMap<>();
        for (SpreadModel.Sample s : ctx.spreads().samples()) {
            grid.computeIfAbsent(s.symbol() + " " + s.quarter(), k -> new TreeMap<>()).put(s.bucket(), String.format("%.5f", s.median()));
        }
        h.append("<table><tr><th>Symbol quarter</th><th>ASIA</th><th>LONDON</th><th>OVERLAP</th><th>NEW_YORK</th><th>ROLLOVER</th></tr>");
        grid.forEach((k, v) -> h.append("<tr><td>").append(k).append("</td><td>").append(v.getOrDefault("ASIA", "-")).append("</td><td>")
                .append(v.getOrDefault("LONDON", "-")).append("</td><td>").append(v.getOrDefault("OVERLAP", "-")).append("</td><td>")
                .append(v.getOrDefault("NEW_YORK", "-")).append("</td><td>").append(v.getOrDefault("ROLLOVER", "-")).append("</td></tr>"));
        h.append("</table><h3>Costs</h3><p>").append(esc(ctx.costs())).append("</p><p>News calendar: ").append(ctx.news().events().size())
                .append(" MAJOR events, coverage ").append(ctx.news().coverFrom()).append(" to ").append(ctx.news().coverTo()).append(".</p></body></html>");
        return h.toString();
    }
}
