package dev.forexsignals.indicator;

import dev.forexsignals.market.Candle;
import dev.forexsignals.market.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Hand-built candle fixtures; expected values worked out by hand from Wilder's / the standard formulas (see comments). */
class IndicatorFixtureTest {

    private static int i;

    private static Candle c(double h, double l, double close) {
        Instant t = Instant.parse("2024-01-17T10:00:00Z").plusSeconds(900L * i++);
        return new Candle("f", "EURUSD", Timeframe.M15, t, t.plusSeconds(900), close, h, l, close, 1, Double.NaN, Double.NaN, Double.NaN, Candle.Source.DERIVED);
    }

    private static Candle close(double x) { return c(x, x, x); }

    @Test
    void emaSeedsWithSmaThenSmooths() {
        // EMA(3), alpha = 0.5. Closes 1..6: SMA(1,2,3) = 2; then 0.5*4+0.5*2 = 3; 0.5*5+0.5*3 = 4; 0.5*6+0.5*4 = 5.
        Ema e = new Ema(3);
        e.update(close(1));
        e.update(close(2));
        assertThat(e.ready()).isFalse();
        assertThat(e.value()).isNaN();
        e.update(close(3));
        assertThat(e.value()).isEqualTo(2.0);
        e.update(close(4));
        assertThat(e.value()).isEqualTo(3.0);
        e.update(close(5));
        e.update(close(6));
        assertThat(e.value()).isEqualTo(5.0);
    }

    @Test
    void atrIsWilderSmoothedTrueRangeIncludingGaps() {
        // ATR(3). TRs: 2 (h-l, no previous close), 2, 3 -> ATR = 7/3. Next TR 3 -> (7/3*2+3)/3 = 23/9.
        // Gap bar (15,14,14.5) after close 9: TR = max(1, 6, 5) = 6 -> (23/9*2+6)/3 = 100/27.
        Atr a = new Atr(3);
        a.update(c(10, 8, 9));
        a.update(c(11, 9, 10));
        assertThat(a.value()).isNaN();
        a.update(c(12, 9, 11));
        assertThat(a.value()).isCloseTo(7.0 / 3, within(1e-12));
        a.update(c(11, 8, 9));
        assertThat(a.value()).isCloseTo(23.0 / 9, within(1e-12));
        a.update(c(15, 14, 14.5));
        assertThat(a.value()).isCloseTo(100.0 / 27, within(1e-12));
    }

    @Test
    void rsiIsWilder() {
        // RSI(3). Closes 10, 11, 10.5, 11.5: changes +1, -0.5, +1 -> avgGain 2/3, avgLoss 1/6, RS 4, RSI 80.
        // Close 11 (-0.5): avgGain 4/9, avgLoss 5/18, RS 1.6, RSI 100 - 100/2.6. Close 12 (+1): avgGain 17/27, avgLoss 5/27, RS 3.4.
        Rsi r = new Rsi(3);
        for (double x : new double[]{10, 11, 10.5}) r.update(close(x));
        assertThat(r.ready()).isFalse();
        r.update(close(11.5));
        assertThat(r.value()).isCloseTo(80.0, within(1e-9));
        r.update(close(11));
        assertThat(r.value()).isCloseTo(100 - 100 / 2.6, within(1e-9));
        r.update(close(12));
        assertThat(r.value()).isCloseTo(100 - 100 / 4.4, within(1e-9));
        Rsi flat = new Rsi(2);
        for (int k = 0; k < 4; k++) flat.update(close(1.0));
        assertThat(flat.value()).isEqualTo(50.0);
    }

    @Test
    void adxIsWilder() {
        // ADX(2). Step by step (TR, +DM, -DM -> Wilder sums -> DI -> DX):
        //  bar2 (11,9,10.5): TR 2, +DM 1;  bar3 (12,10,11.5): TR 2, +DM 1 -> sums TR 4, +2, -0 -> DX 100
        //  bar4 (11.5,9.5,10): -DM 0.5 -> sums 4, 1, 0.5 -> DX 33.333 -> ADX = (100 + 33.333)/2 = 66.667
        //  bar5 (10.5,8.5,9): -DM 1 -> sums 4, 0.5, 1.25 -> DX 42.857 -> ADX (66.667 + 42.857)/2 = 54.762
        //  bar6 (11,9,10.5): +DM 0.5 -> sums 4, 0.75, 0.625 -> DX 9.0909 -> ADX 31.926
        //  bar7 (13,10.5,12.5): TR 2.5, +DM 2 -> sums 4.5, 2.375, 0.3125 -> DX 76.744 -> ADX 54.335
        Adx a = new Adx(2);
        double[][] bars = {{10, 8, 9}, {11, 9, 10.5}, {12, 10, 11.5}, {11.5, 9.5, 10}, {10.5, 8.5, 9}, {11, 9, 10.5}, {13, 10.5, 12.5}};
        double[] expected = {Double.NaN, Double.NaN, Double.NaN, 200.0 / 3, 54.76190476190476, 31.926406926406926, 54.335296486459285};
        for (int k = 0; k < bars.length; k++) {
            a.update(c(bars[k][0], bars[k][1], bars[k][2]));
            if (Double.isNaN(expected[k])) assertThat(a.value()).as("bar " + (k + 1)).isNaN();
            else assertThat(a.value()).as("bar " + (k + 1)).isCloseTo(expected[k], within(1e-9));
        }
    }

    @Test
    void donchianCanExcludeTheSignalBar() {
        // Donchian(3) highs 5, 7, 6, 9: including the latest bar the high is 9; the 3 bars BEFORE it give 7.
        Donchian d = new Donchian(3);
        d.update(c(5, 1, 3));
        d.update(c(7, 2, 3));
        d.update(c(6, 0.5, 3));
        assertThat(d.highest(true)).as("needs n+1 bars when excluding the latest").isNaN();
        assertThat(d.highest(false)).isEqualTo(7);
        d.update(c(9, 4, 8));
        assertThat(d.highest(false)).isEqualTo(9);
        assertThat(d.highest(true)).isEqualTo(7);
        assertThat(d.lowest(true)).isEqualTo(0.5);
        assertThat(d.lowest(false)).isEqualTo(0.5);
        d.update(c(8, 3, 7));
        assertThat(d.lowest(false)).isEqualTo(0.5);
        assertThat(d.lowest(true)).isEqualTo(0.5);
        d.update(c(8, 3, 7));
        assertThat(d.lowest(false)).isEqualTo(3);
    }
}
