package dev.forexsignals.strategy.api;

import dev.forexsignals.market.Candle;
import dev.forexsignals.market.Timeframe;

import java.time.Instant;
import java.util.OptionalDouble;
import java.util.random.RandomGenerator;

/**
 * Everything a strategy may see at a candle close: closed candles and declared indicators of its own symbol, by
 * "bars ago" (0 = the latest CLOSED bar of that timeframe at {@link #now()}). There is deliberately no access to
 * ticks, the forming bar, other symbols' data, the database, the feed or the wall clock.
 * Violations throw {@link LookaheadViolation}.
 */
public interface StrategyContext {

    String symbol();

    /** Close time of the entry-timeframe candle being evaluated. */
    Instant now();

    /** The ago-th latest closed candle of tf (0 = latest). Throws on ago < 0 or on a bar closing after now. */
    Candle bar(Timeframe tf, int ago);

    /** Number of closed bars available for tf (including those before the warm-up). */
    int bars(Timeframe tf);

    /** Value of a declared indicator ago bars back; empty while it is not valid or still warming up. */
    OptionalDouble ind(Timeframe tf, IndicatorKind kind, int period, int ago);

    default OptionalDouble ind(Timeframe tf, IndicatorKind kind, int period) { return ind(tf, kind, period, 0); }

    /** Donchian channel of n bars on tf; excludeLatest = use the n bars BEFORE the latest closed bar. */
    OptionalDouble donchianHigh(Timeframe tf, int n, boolean excludeLatest);

    OptionalDouble donchianLow(Timeframe tf, int n, boolean excludeLatest);

    /**
     * A closed bar of a declared CONTEXT symbol (meta().contextSymbols()), looked up by its open time. Empty if that symbol
     * has no bar with that open time. Asking for a bar that cannot have closed by now (its period ends after now) throws
     * LookaheadViolation even if the bar does not exist yet.
     */
    java.util.Optional<Candle> contextBar(String symbol, Timeframe tf, Instant openTime);

    /** A declared context indicator of a context symbol at the bar with this open time (same guard; empty while warming up). */
    OptionalDouble contextInd(String symbol, Timeframe tf, IndicatorKind kind, int period, Instant openTime);

    /** Seeded random numbers (used only by the random-entry controls); reproducible per run, strategy and symbol. */
    RandomGenerator random();
}
