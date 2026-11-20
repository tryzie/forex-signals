package dev.forexsignals.stats;

import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Block bootstrap by ISO week (PLAN §8 check 2 and 8): weeks are resampled with replacement (keeping each week's trades
 * together, so within-week correlation is preserved); the statistic is the mean net R per trade of the resample.
 * 10,000 resamples, seeded. The 90% CI is the 5th and 95th percentile.
 */
public final class Bootstrap {

    public record Ci(double mean, double lo, double hi, int weeks, int resamples) {}

    private Bootstrap() {}

    static String week(TradeR t) {
        var d = t.exit().atZone(ZoneOffset.UTC);
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + String.format("%02d", d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    public static Ci meanCi(List<TradeR> trades, int resamples, long seed) { return meanCi(trades, resamples, seed, 0.90); }

    /** Two-sided CI at the given level (0.90 by default; 0.95 = Bonferroni-adjusted 90% for two hypotheses). */
    public static Ci meanCi(List<TradeR> trades, int resamples, long seed, double level) {
        Map<String, double[]> byWeek = group(trades, TradeR::netR);
        List<double[]> weeks = new ArrayList<>(byWeek.values());
        if (weeks.isEmpty()) return new Ci(Double.NaN, Double.NaN, Double.NaN, 0, resamples);
        SplittableRandom r = new SplittableRandom(seed);
        double[] stats = new double[resamples];
        for (int b = 0; b < resamples; b++) {
            double sum = 0;
            int n = 0;
            for (int k = 0; k < weeks.size(); k++) {
                double[] w = weeks.get(r.nextInt(weeks.size()));
                for (double v : w) sum += v;
                n += w.length;
            }
            stats[b] = sum / n;
        }
        Arrays.sort(stats);
        double mean = trades.stream().mapToDouble(TradeR::netR).average().orElse(Double.NaN);
        double tail = (1 - level) / 2;
        return new Ci(mean, stats[(int) (tail * resamples)], stats[(int) ((1 - tail) * resamples) - 1], weeks.size(), resamples);
    }

    /** CI of (mean A - mean B), resampling the union of weeks jointly (check 8: parent vs its random control). */
    public static Ci differenceCi(List<TradeR> a, List<TradeR> b, int resamples, long seed) { return differenceCi(a, b, resamples, seed, 0.90); }

    public static Ci differenceCi(List<TradeR> a, List<TradeR> b, int resamples, long seed, double level) {
        Map<String, double[]> wa = group(a, TradeR::netR), wb = group(b, TradeR::netR);
        List<String> weeks = new ArrayList<>(new java.util.TreeSet<>(wa.keySet()) {{ addAll(wb.keySet()); }});
        if (weeks.isEmpty()) return new Ci(Double.NaN, Double.NaN, Double.NaN, 0, resamples);
        SplittableRandom r = new SplittableRandom(seed);
        double[] stats = new double[resamples];
        int valid = 0;
        for (int k = 0; k < resamples; k++) {
            double sa = 0, sb = 0;
            int na = 0, nb = 0;
            for (int j = 0; j < weeks.size(); j++) {
                String w = weeks.get(r.nextInt(weeks.size()));
                double[] x = wa.get(w), y = wb.get(w);
                if (x != null) { for (double v : x) sa += v; na += x.length; }
                if (y != null) { for (double v : y) sb += v; nb += y.length; }
            }
            if (na > 0 && nb > 0) stats[valid++] = sa / na - sb / nb;
        }
        if (valid == 0) return new Ci(Double.NaN, Double.NaN, Double.NaN, weeks.size(), resamples);
        double[] s = Arrays.copyOf(stats, valid);
        Arrays.sort(s);
        double diff = a.stream().mapToDouble(TradeR::netR).average().orElse(Double.NaN) - b.stream().mapToDouble(TradeR::netR).average().orElse(Double.NaN);
        double tail = (1 - level) / 2;
        return new Ci(diff, s[(int) (tail * valid)], s[Math.max(0, (int) ((1 - tail) * valid) - 1)], weeks.size(), valid);
    }

    private static Map<String, double[]> group(List<TradeR> t, Function<TradeR, Double> f) {
        Map<String, List<Double>> m = new TreeMap<>();
        for (TradeR x : t) m.computeIfAbsent(week(x), k -> new ArrayList<>()).add(f.apply(x));
        Map<String, double[]> out = new TreeMap<>();
        m.forEach((k, v) -> out.put(k, v.stream().mapToDouble(Double::doubleValue).toArray()));
        return out;
    }
}
