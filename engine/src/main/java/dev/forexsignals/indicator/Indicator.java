package dev.forexsignals.indicator;

import dev.forexsignals.market.Candle;

/**
 * An incremental indicator over CLOSED candles of one symbol and timeframe. {@link #update} is called once per
 * closed bar, in time order; {@link #value} is NaN until the indicator is mathematically valid ({@link #ready}).
 * The strategy runner adds the warm-up rule on top (3x the longest period + 10 bars before any use).
 */
public interface Indicator {
    void update(Candle closed);

    boolean ready();

    double value();

    /** Number of bars needed before {@link #ready()} can be true. */
    int minBars();
}
