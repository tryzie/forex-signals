package dev.forexsignals.history;

import dev.forexsignals.market.Candle;
import dev.forexsignals.market.SessionCalendar;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.store.MarketStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Verification of the broker USDX against the pre-registered criteria V1-V4 (docs/hyp/eurusd-confirmation.md §10.1).
 * Uses the derived H1 bid candles. USDX_SYN here is the ICE formula on H1 bid closes of its six inputs, for this check and
 * descriptive cross-checks only; it is never a gate input.
 */
public final class UsdxCheck {

    public record Spec(Integer digits, Double contractSize, Double minVolume, Double volumeStep, String hours, long specChanges) {}

    public record Result(Spec spec, boolean v1, int sessionHoursEurusd, int sessionHoursUsdx, double coverage, boolean v2,
                         int returns, double corrSyn, double corrEurusd, boolean v3, List<String> jumps, boolean v4) {
        public boolean clean() { return v1 && v2 && v3 && v4; }
    }

    static final String[] SYN = {"EURUSD", "USDJPY", "GBPUSD", "USDCAD", "USDSEK", "USDCHF"};
    static final double[] W = {-0.576, 0.136, -0.119, 0.091, 0.042, 0.036};

    private UsdxCheck() {}

    public static double synthetic(double[] px) {
        double v = 50.14348112;
        for (int i = 0; i < px.length; i++) v *= Math.pow(px[i], W[i]);
        return v;
    }

    public static Result run(MarketStore store, String feed, Instant from, Instant to, Spec spec, boolean specMatches) {
        Map<String, Map<Instant, Double>> h1 = new HashMap<>();
        for (String s : new String[]{"USDX", "EURUSD", "USDJPY", "GBPUSD", "USDCAD", "USDSEK", "USDCHF"}) {
            Map<Instant, Double> m = new TreeMap<>();
            for (Candle c : store.loadDerived(feed, s, Timeframe.H1, from, to)) m.put(c.openTime(), c.close());
            h1.put(s, m);
        }
        Map<Instant, Double> usdx = h1.get("USDX"), eur = h1.get("EURUSD");
        // V2: in-session hours with an EURUSD bar that also have a USDX bar.
        int sessEur = 0, sessBoth = 0;
        for (Instant t : eur.keySet()) {
            if (!(SessionCalendar.londonOpen(t) || SessionCalendar.newYorkOpen(t))) continue;
            sessEur++;
            if (usdx.containsKey(t)) sessBoth++;
        }
        double coverage = sessEur == 0 ? 0 : (double) sessBoth / sessEur;
        // Synthetic series on hours where all six inputs exist.
        Map<Instant, Double> syn = new TreeMap<>();
        for (Instant t : eur.keySet()) {
            double[] px = new double[SYN.length];
            boolean ok = true;
            for (int i = 0; i < SYN.length && ok; i++) {
                Double v = h1.get(SYN[i]).get(t);
                if (v == null) ok = false; else px[i] = v;
            }
            if (ok) syn.put(t, synthetic(px));
        }
        // V3/V4: consecutive-hour log returns where USDX, SYN and EURUSD all have both bars.
        List<double[]> r = new ArrayList<>();
        List<String> jumps = new ArrayList<>();
        Instant prev = null;
        for (Instant t : usdx.keySet()) {
            if (prev != null && t.minusSeconds(3600).equals(prev) && syn.containsKey(t) && syn.containsKey(prev) && eur.containsKey(t) && eur.containsKey(prev)) {
                double ru = Math.log(usdx.get(t) / usdx.get(prev)), rs = Math.log(syn.get(t) / syn.get(prev)), re = Math.log(eur.get(t) / eur.get(prev));
                r.add(new double[]{ru, rs, re});
                if (Math.abs(ru) > 0.003 && Math.abs(rs) < Math.abs(ru) / 3) {
                    jumps.add(String.format("%s USDX %+.3f%% vs USDX_SYN %+.3f%%", t, 100 * ru, 100 * rs));
                }
            }
            prev = t;
        }
        double cs = corr(r, 0, 1), ce = corr(r, 0, 2);
        return new Result(spec, specMatches && spec.specChanges() == 0, sessEur, sessBoth, coverage, coverage >= 0.98, r.size(), cs, ce,
                cs >= 0.90 && ce <= -0.80, jumps, jumps.isEmpty());
    }

    static double corr(List<double[]> r, int a, int b) {
        int n = r.size();
        if (n < 3) return Double.NaN;
        double ma = 0, mb = 0;
        for (double[] x : r) { ma += x[a]; mb += x[b]; }
        ma /= n; mb /= n;
        double sab = 0, saa = 0, sbb = 0;
        for (double[] x : r) { sab += (x[a] - ma) * (x[b] - mb); saa += (x[a] - ma) * (x[a] - ma); sbb += (x[b] - mb) * (x[b] - mb); }
        return sab / Math.sqrt(saa * sbb);
    }

    public static Path write(Path dir, Instant at, Result r) throws IOException {
        Files.createDirectories(dir);
        StringBuilder md = new StringBuilder("# USDX verification (pre-registered criteria V1-V4)\n\nRun at " + at + " UTC.\n\n");
        md.append("| Criterion | Observed | Rule | Pass |\n|---|---|---|---|\n");
        md.append("| V1 spec | ").append(r.spec()).append(" | digits 3, lot 100, min 1, step 1, reviewed schedule, no SPEC_CHANGED | ").append(r.v1()).append(" |\n");
        md.append(String.format("| V2 coverage | %d of %d in-session EURUSD hours (%.2f%%) | >= 98%% | %s |%n", r.sessionHoursUsdx(), r.sessionHoursEurusd(), 100 * r.coverage(), r.v2()));
        md.append(String.format("| V3 consistency | corr(USDX, USDX_SYN) %.3f, corr(USDX, EURUSD) %.3f over %d H1 returns | >= 0.90 and <= -0.80 | %s |%n",
                r.corrSyn(), r.corrEurusd(), r.returns(), r.v3()));
        md.append("| V4 artificial jumps | ").append(r.jumps().size()).append(" | none | ").append(r.v4()).append(" |\n\n");
        md.append("**Verified clean: ").append(r.clean() ? "YES" : "NO").append("**\n");
        if (!r.jumps().isEmpty()) {
            md.append("\n## V4 jumps (USDX > 0.30% in one H1 bar while USDX_SYN moved less than a third of that)\n\n");
            r.jumps().forEach(j -> md.append("- ").append(j).append('\n'));
        }
        Path out = dir.resolve("usdx-verification-" + at.toString().replace(':', '-') + ".md");
        Files.writeString(out, md);
        return out;
    }
}
