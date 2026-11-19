package dev.forexsignals.indicator;

import dev.forexsignals.market.Candle;

/**
 * Average true range, Wilder. TR = max(high - low, |high - prevClose|, |low - prevClose|); the first bar has no
 * previous close, so its TR is high - low. ATR(n) = SMA of the first n TRs, then (ATR * (n - 1) + TR) / n.
 */
public final class Atr implements Indicator {
    private final int n;
    private int count;
    private double prevClose = Double.NaN, sum, value = Double.NaN;

    public Atr(int n) {
        if (n < 1) throw new IllegalArgumentException("period");
        this.n = n;
    }

    @Override
    public void update(Candle c) {
        double tr = Double.isNaN(prevClose) ? c.high() - c.low()
                : Math.max(c.high() - c.low(), Math.max(Math.abs(c.high() - prevClose), Math.abs(c.low() - prevClose)));
        prevClose = c.close();
        count++;
        if (count < n) sum += tr;
        else if (count == n) { sum += tr; value = sum / n; }
        else value = (value * (n - 1) + tr) / n;
    }

    @Override public boolean ready() { return count >= n; }

    @Override public double value() { return ready() ? value : Double.NaN; }

    @Override public int minBars() { return n; }
}
