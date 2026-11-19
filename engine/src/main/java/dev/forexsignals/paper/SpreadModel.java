package dev.forexsignals.paper;

import dev.forexsignals.market.SessionCalendar;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Modelled spread for bid-only history (PLAN §14.4): the median spread of the sampled tick-history week of the same
 * quarter, per symbol and session bucket. A quarter without a sample uses the nearest sampled quarter; a bucket without
 * samples uses the median of that quarter's buckets. This is an approximation; reports say so and cost stress widens it.
 */
public final class SpreadModel {

    public record Sample(String symbol, String quarter, Instant weekStart, String bucket, double median, double p95, long ticks) {}

    private final Map<String, TreeMap<Instant, Map<String, Double>>> bySymbol = new HashMap<>(); // symbol -> weekStart -> bucket -> median
    private final List<Sample> samples;

    public SpreadModel(List<Sample> samples) {
        this.samples = List.copyOf(samples);
        for (Sample s : samples) {
            bySymbol.computeIfAbsent(s.symbol(), k -> new TreeMap<>()).computeIfAbsent(s.weekStart(), k -> new HashMap<>()).put(s.bucket(), s.median());
        }
    }

    public List<Sample> samples() { return samples; }

    public boolean covers(String symbol) { return bySymbol.containsKey(symbol); }

    /** Quarter label of an instant, e.g. 2024Q1. */
    public static String quarter(Instant t) {
        var d = t.atZone(ZoneOffset.UTC);
        return d.getYear() + "Q" + ((d.getMonthValue() - 1) / 3 + 1);
    }

    public double median(String symbol, Instant t) {
        TreeMap<Instant, Map<String, Double>> weeks = bySymbol.get(symbol);
        if (weeks == null || weeks.isEmpty()) throw new IllegalStateException("no spread samples for " + symbol + " (run --mode=spreadsample)");
        // Same quarter if sampled, else the nearest sampled week in time.
        Map<String, Double> week = null;
        for (var e : weeks.entrySet()) if (quarter(e.getKey()).equals(quarter(t))) week = e.getValue();
        if (week == null) {
            var lo = weeks.floorEntry(t);
            var hi = weeks.ceilingEntry(t);
            week = lo == null ? hi.getValue() : hi == null ? lo.getValue()
                    : (t.toEpochMilli() - lo.getKey().toEpochMilli() <= hi.getKey().toEpochMilli() - t.toEpochMilli() ? lo.getValue() : hi.getValue());
        }
        Double m = week.get(SessionCalendar.bucket(t).name());
        if (m != null) return m;
        double[] v = week.values().stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return v[v.length / 2];
    }
}
