package dev.forexsignals.market;

import java.time.Instant;

/**
 * A closed bid candle. Spread fields are NaN when the source had no ask. For M1 built from ticks,
 * spreadMed is the median tick spread; for derived timeframes it is the median of the M1 medians.
 */
public record Candle(String feed, String symbol, Timeframe timeframe, Instant openTime, Instant closeTime,
                     double open, double high, double low, double close, int tickCount,
                     double spreadMin, double spreadMed, double spreadMax, Source source) {

    public enum Source { BUILT, IMPORT, DERIVED }
}
