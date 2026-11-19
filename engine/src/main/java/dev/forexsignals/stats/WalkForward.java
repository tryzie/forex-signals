package dev.forexsignals.stats;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Walk-forward (PLAN §8 check 5): windows of 12 months in-sample then 3 months out-of-sample, stepped 3 months, from the
 * start of the data. The parameters are shared and frozen (never re-fitted per window), so "in-sample" means "data the
 * frozen parameters could have been chosen on"; every trade is assigned to windows by its entry time.
 * Pooled OOS: expectancy > 0, >= 50% of the pooled IS expectancy, and >= 50 OOS trades (else INCONCLUSIVE).
 */
public final class WalkForward {

    public record Window(Instant isFrom, Instant oosFrom, Instant oosTo, int isTrades, double isExpectancy, int oosTrades, double oosExpectancy) {}

    public record Result(List<Window> windows, int oosTrades, double oosExpectancy, double isExpectancy, String verdict) {}

    private WalkForward() {}

    public static Result run(List<TradeR> trades, Instant dataFrom, Instant dataTo) {
        List<Window> out = new ArrayList<>();
        List<TradeR> oosAll = new ArrayList<>(), isAll = new ArrayList<>();
        Instant isFrom = dataFrom;
        while (true) {
            Instant oosFrom = isFrom.atZone(ZoneOffset.UTC).plusMonths(12).toInstant();
            Instant oosTo = oosFrom.atZone(ZoneOffset.UTC).plusMonths(3).toInstant();
            if (oosTo.isAfter(dataTo)) break;
            final Instant a = isFrom;
            List<TradeR> is = trades.stream().filter(t -> !t.entry().isBefore(a) && t.entry().isBefore(oosFrom)).toList();
            List<TradeR> oos = trades.stream().filter(t -> !t.entry().isBefore(oosFrom) && t.entry().isBefore(oosTo)).toList();
            out.add(new Window(isFrom, oosFrom, oosTo, is.size(), mean(is), oos.size(), mean(oos)));
            isAll.addAll(is);
            oosAll.addAll(oos);
            isFrom = isFrom.atZone(ZoneOffset.UTC).plusMonths(3).toInstant();
        }
        double oosE = mean(oosAll), isE = mean(isAll);
        String verdict;
        if (out.isEmpty() || oosAll.size() < 50) verdict = "INCONCLUSIVE";
        else verdict = oosE > 0 && (isE <= 0 ? false : oosE >= 0.5 * isE) ? "PASS" : "FAIL";
        return new Result(out, oosAll.size(), oosE, isE, verdict);
    }

    static double mean(List<TradeR> t) { return t.isEmpty() ? Double.NaN : t.stream().mapToDouble(TradeR::netR).average().orElse(Double.NaN); }
}
