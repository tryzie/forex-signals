package dev.forexsignals.backtest;

import dev.forexsignals.market.Candle;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.store.MarketStore;

import java.time.Instant;
import java.util.Arrays;

/** One symbol's M1 bid bars in primitive arrays (3 years of M1 is about 1.1M bars, ~45 MB). */
public final class M1Series {
    public final String feed, symbol;
    public long[] open = new long[1 << 16];
    public double[] o = new double[1 << 16], h = new double[1 << 16], l = new double[1 << 16], c = new double[1 << 16];
    public int n;

    public M1Series(String feed, String symbol) {
        this.feed = feed;
        this.symbol = symbol;
    }

    public void add(Candle k) {
        if (n == open.length) {
            int m = n * 2;
            open = Arrays.copyOf(open, m); o = Arrays.copyOf(o, m); h = Arrays.copyOf(h, m); l = Arrays.copyOf(l, m); c = Arrays.copyOf(c, m);
        }
        open[n] = k.openTime().toEpochMilli(); o[n] = k.open(); h[n] = k.high(); l[n] = k.low(); c[n] = k.close();
        n++;
    }

    public static M1Series load(MarketStore store, String feed, String symbol, Instant from, Instant to) {
        M1Series s = new M1Series(feed, symbol);
        store.streamM1(feed, symbol, from, to, s::add);
        return s;
    }

    public Candle candle(int i) {
        Instant t = Instant.ofEpochMilli(open[i]);
        return new Candle(feed, symbol, Timeframe.M1, t, t.plusSeconds(60), o[i], h[i], l[i], c[i], 0, Double.NaN, Double.NaN, Double.NaN, Candle.Source.IMPORT);
    }

    /** The bar containing t, or null. */
    public Candle at(Instant t) {
        long m = Math.floorDiv(t.toEpochMilli(), 60_000L) * 60_000L;
        int i = Arrays.binarySearch(open, 0, n, m);
        return i >= 0 ? candle(i) : null;
    }
}
