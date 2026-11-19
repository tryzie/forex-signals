package dev.forexsignals.strategy;

import dev.forexsignals.market.Candle;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.strategy.api.Strategy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hand-shaped multi-timeframe fixtures: per timeframe a list of (open, high, low, close) bars that all END at the same
 * instant. {@link #run} feeds them to a StrategyRunner in close-time order (equal close times in one batch) and returns
 * the result of the LAST batch.
 */
public final class Series {
    public static final Instant END = Instant.parse("2024-03-06T15:00:00Z");
    private final Map<Timeframe, List<double[]>> bars = new EnumMap<>(Timeframe.class);
    private final String symbol;

    public Series(String symbol) { this.symbol = symbol; }

    /** Adds bars: each row {open, high, low, close}. */
    public Series add(Timeframe tf, List<double[]> rows) {
        bars.computeIfAbsent(tf, k -> new ArrayList<>()).addAll(rows);
        return this;
    }

    /** n bars trending by step per bar from start, range +/- half on each side of the open-close body. */
    public static List<double[]> trend(int n, double start, double step, double half) {
        List<double[]> out = new ArrayList<>();
        double prev = start;
        for (int i = 0; i < n; i++) {
            double c = prev + step;
            out.add(new double[]{prev, Math.max(prev, c) + half, Math.min(prev, c) - half, c});
            prev = c;
        }
        return out;
    }

    /** n bars alternating up/down by amp around level (a range). */
    public static List<double[]> chop(int n, double level, double amp) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double o = level + (i % 2 == 0 ? -amp : amp), c = level + (i % 2 == 0 ? amp : -amp);
            out.add(new double[]{o, Math.max(o, c) + amp / 4, Math.min(o, c) - amp / 4, c});
        }
        return out;
    }

    public static double last(List<double[]> rows) { return rows.getLast()[3]; }

    public List<Candle> candles() {
        List<Candle> all = new ArrayList<>();
        bars.forEach((tf, rows) -> {
            Instant start = END.minus(tf.duration().multipliedBy(rows.size()));
            for (int i = 0; i < rows.size(); i++) {
                Instant o = start.plus(tf.duration().multipliedBy(i));
                double[] r = rows.get(i);
                all.add(new Candle("test", symbol, tf, o, o.plus(tf.duration()), r[0], r[1], r[2], r[3], 10, Double.NaN, Double.NaN, Double.NaN, Candle.Source.DERIVED));
            }
        });
        all.sort(Comparator.comparing(Candle::closeTime));
        return all;
    }

    public StrategyRunner.Result run(StrategyRunner runner) {
        TreeMap<Instant, List<Candle>> batches = new TreeMap<>();
        for (Candle c : candles()) batches.computeIfAbsent(c.closeTime(), k -> new ArrayList<>()).add(c);
        StrategyRunner.Result last = null;
        for (var e : batches.entrySet()) last = runner.onCandlesClosed(e.getKey(), e.getValue(), (id, s) -> Map.of());
        return last;
    }

    public StrategyRunner.Result run(Strategy s) { return run(new StrategyRunner(List.of(s), 1)); }

    public static Duration d(Timeframe tf) { return tf.duration(); }
}
