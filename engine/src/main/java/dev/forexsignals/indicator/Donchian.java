package dev.forexsignals.indicator;

import dev.forexsignals.market.Candle;

/**
 * Donchian channel over the last n CLOSED bars. {@link #highest(boolean)} / {@link #lowest(boolean)} with
 * excludeLatest=true use the n bars BEFORE the latest one (the breakout rule compares the signal bar's close with the
 * previous 20 bars, so the signal bar itself must not be in the channel).
 */
public final class Donchian implements Indicator {
    private final int n;
    private final double[] highs, lows;
    private int count;

    public Donchian(int n) {
        if (n < 1) throw new IllegalArgumentException("period");
        this.n = n;
        this.highs = new double[n + 1];
        this.lows = new double[n + 1];
    }

    @Override
    public void update(Candle c) {
        highs[count % (n + 1)] = c.high();
        lows[count % (n + 1)] = c.low();
        count++;
    }

    private boolean ready(boolean excludeLatest) { return count >= (excludeLatest ? n + 1 : n); }

    public double highest(boolean excludeLatest) {
        if (!ready(excludeLatest)) return Double.NaN;
        double m = Double.NEGATIVE_INFINITY;
        int last = count - (excludeLatest ? 2 : 1);
        for (int i = 0; i < n; i++) m = Math.max(m, highs[Math.floorMod(last - i, n + 1)]);
        return m;
    }

    public double lowest(boolean excludeLatest) {
        if (!ready(excludeLatest)) return Double.NaN;
        double m = Double.POSITIVE_INFINITY;
        int last = count - (excludeLatest ? 2 : 1);
        for (int i = 0; i < n; i++) m = Math.min(m, lows[Math.floorMod(last - i, n + 1)]);
        return m;
    }

    @Override public boolean ready() { return ready(false); }

    /** The upper band over the last n bars including the latest (use highest(true) for breakouts). */
    @Override public double value() { return highest(false); }

    @Override public int minBars() { return n + 1; }
}
