package dev.forexsignals.indicator;

import dev.forexsignals.market.Candle;

/**
 * Relative strength index, Wilder. Changes are close-to-close, so the first bar only sets the reference.
 * Average gain/loss = SMA of the first n changes, then (avg * (n - 1) + x) / n. RSI = 100 - 100 / (1 + avgGain/avgLoss);
 * 100 when avgLoss is 0 (and 50 when both are 0).
 */
public final class Rsi implements Indicator {
    private final int n;
    private int changes;
    private double prev = Double.NaN, gainSum, lossSum, avgGain, avgLoss;

    public Rsi(int n) {
        if (n < 1) throw new IllegalArgumentException("period");
        this.n = n;
    }

    @Override
    public void update(Candle c) { add(c.close()); }

    public void add(double close) {
        if (Double.isNaN(prev)) { prev = close; return; }
        double ch = close - prev;
        prev = close;
        double g = Math.max(ch, 0), l = Math.max(-ch, 0);
        changes++;
        if (changes < n) { gainSum += g; lossSum += l; }
        else if (changes == n) { gainSum += g; lossSum += l; avgGain = gainSum / n; avgLoss = lossSum / n; }
        else { avgGain = (avgGain * (n - 1) + g) / n; avgLoss = (avgLoss * (n - 1) + l) / n; }
    }

    @Override public boolean ready() { return changes >= n; }

    @Override
    public double value() {
        if (!ready()) return Double.NaN;
        if (avgLoss == 0) return avgGain == 0 ? 50.0 : 100.0;
        return 100.0 - 100.0 / (1.0 + avgGain / avgLoss);
    }

    @Override public int minBars() { return n + 1; }
}
