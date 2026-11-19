package dev.forexsignals.indicator;

import dev.forexsignals.market.Candle;

/** Exponential moving average of closes: seeded with the SMA of the first n closes, then alpha = 2 / (n + 1). */
public final class Ema implements Indicator {
    private final int n;
    private final double alpha;
    private int count;
    private double sum, value = Double.NaN;

    public Ema(int n) {
        if (n < 1) throw new IllegalArgumentException("period");
        this.n = n;
        this.alpha = 2.0 / (n + 1);
    }

    @Override
    public void update(Candle c) { add(c.close()); }

    public void add(double x) {
        count++;
        if (count < n) {
            sum += x;
        } else if (count == n) {
            sum += x;
            value = sum / n;
        } else {
            value = alpha * x + (1 - alpha) * value;
        }
    }

    @Override public boolean ready() { return count >= n; }

    @Override public double value() { return ready() ? value : Double.NaN; }

    @Override public int minBars() { return n; }
}
