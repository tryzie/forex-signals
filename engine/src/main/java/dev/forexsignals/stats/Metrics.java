package dev.forexsignals.stats;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Per-strategy performance in R (PLAN §8). Everything is computed on NET R unless named gross. */
public record Metrics(int trades, double expectancy, double grossExpectancy, double avgCostR, double winRate, double profitFactor,
                      double maxDrawdownR, double totalR, double months, Map<String, Double> positiveShareBySymbol,
                      double worstCaseFillExpectancy) {

    public static Metrics of(List<TradeR> t) {
        int n = t.size();
        if (n == 0) return new Metrics(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, 0, 0, Map.of(), Double.NaN);
        double sum = 0, gross = 0, wins = 0, pos = 0, neg = 0, worst = 0;
        int worstN = 0;
        double eq = 0, peak = 0, dd = 0;
        Map<String, Double> bySym = new TreeMap<>();
        for (TradeR x : t) {
            sum += x.netR();
            gross += x.grossR();
            if (x.netR() > 0) { wins++; pos += x.netR(); } else neg += -x.netR();
            if (!Double.isNaN(x.worstNetR())) { worst += x.worstNetR(); worstN++; }
            eq += x.netR();
            peak = Math.max(peak, eq);
            dd = Math.max(dd, peak - eq);
            bySym.merge(x.symbol(), x.netR(), Double::sum);
        }
        double positiveTotal = bySym.values().stream().mapToDouble(v -> Math.max(0, v)).sum();
        Map<String, Double> share = new TreeMap<>();
        bySym.forEach((k, v) -> share.put(k, positiveTotal == 0 ? 0 : Math.max(0, v) / positiveTotal));
        double months = Duration.between(t.getFirst().entry(), t.getLast().exit()).toDays() / 30.44;
        return new Metrics(n, sum / n, gross / n, (gross - sum) / n, wins / n, neg == 0 ? Double.POSITIVE_INFINITY : pos / neg, dd, sum, months,
                share, worstN == 0 ? Double.NaN : worst / worstN);
    }
}
