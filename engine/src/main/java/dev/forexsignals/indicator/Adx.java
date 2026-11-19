package dev.forexsignals.indicator;

import dev.forexsignals.market.Candle;

/**
 * Average directional index, Wilder.
 *  +DM = up move if up > down and up > 0 (up = high - prevHigh, down = prevLow - low), else 0; -DM mirrored.
 *  TR as in ATR. Over the first n bars with a previous bar, sums of TR, +DM, -DM; afterwards Wilder smoothing of
 *  the sums: S = S - S / n + x. +DI = 100 * S(+DM) / S(TR), -DI likewise, DX = 100 * |+DI - -DI| / (+DI + -DI).
 *  ADX = mean of the first n DX values, then (ADX * (n - 1) + DX) / n. Ready after 2n bars with a previous bar.
 */
public final class Adx implements Indicator {
    private final int n;
    private double prevHigh = Double.NaN, prevLow, prevClose;
    private int moves, dxCount;
    private double sTr, sPlus, sMinus, dxSum, adx = Double.NaN;

    public Adx(int n) {
        if (n < 1) throw new IllegalArgumentException("period");
        this.n = n;
    }

    @Override
    public void update(Candle c) {
        if (Double.isNaN(prevHigh)) { prevHigh = c.high(); prevLow = c.low(); prevClose = c.close(); return; }
        double up = c.high() - prevHigh, down = prevLow - c.low();
        double plusDm = (up > down && up > 0) ? up : 0, minusDm = (down > up && down > 0) ? down : 0;
        double tr = Math.max(c.high() - c.low(), Math.max(Math.abs(c.high() - prevClose), Math.abs(c.low() - prevClose)));
        prevHigh = c.high(); prevLow = c.low(); prevClose = c.close();
        moves++;
        if (moves <= n) {
            sTr += tr; sPlus += plusDm; sMinus += minusDm;
            if (moves < n) return;
        } else {
            sTr = sTr - sTr / n + tr; sPlus = sPlus - sPlus / n + plusDm; sMinus = sMinus - sMinus / n + minusDm;
        }
        double pdi = sTr == 0 ? 0 : 100 * sPlus / sTr, mdi = sTr == 0 ? 0 : 100 * sMinus / sTr;
        double dx = (pdi + mdi) == 0 ? 0 : 100 * Math.abs(pdi - mdi) / (pdi + mdi);
        dxCount++;
        if (dxCount < n) dxSum += dx;
        else if (dxCount == n) { dxSum += dx; adx = dxSum / n; }
        else adx = (adx * (n - 1) + dx) / n;
    }

    @Override public boolean ready() { return dxCount >= n; }

    @Override public double value() { return ready() ? adx : Double.NaN; }

    @Override public int minBars() { return 2 * n; }
}
