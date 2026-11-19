package dev.forexsignals.history;

import dev.forexsignals.market.Candle;
import dev.forexsignals.market.PriceBasis;
import dev.forexsignals.market.SymbolCatalog.SymbolDef;
import dev.forexsignals.market.Tick;
import dev.forexsignals.market.Timeframe;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Historical market data from a feed (cTrader trendbars/tick data, Deriv ticks_history).
 * Every call is one request against the feed's history rate limit; the caller paces them.
 * Implementations return only data inside [from, to) and throw {@link RateLimitedException} when the
 * feed says "slow down", so the caller can back off and retry.
 */
public interface HistorySource extends AutoCloseable {

    String feed();

    PriceBasis priceBasis();

    /** Largest [from, to) span one request may cover for the given data kind. */
    Duration maxSpan(Timeframe tfOrNullForTicks);

    /** Closed M1 candles in [from, to), source IMPORT. */
    List<Candle> fetchM1(SymbolDef symbol, Instant from, Instant to) throws Exception;

    /** Reference candles of one timeframe in [from, to) (e.g. M5 trendbars for the builder check). */
    List<Candle> fetchCandles(SymbolDef symbol, Timeframe tf, Instant from, Instant to) throws Exception;

    /** Raw ticks in [from, to), in time order. */
    List<Tick> fetchTicks(SymbolDef symbol, Instant from, Instant to) throws Exception;

    @Override
    default void close() {}

    /** The feed asked us to slow down. */
    final class RateLimitedException extends Exception {
        public RateLimitedException(String message) { super(message); }
    }
}
