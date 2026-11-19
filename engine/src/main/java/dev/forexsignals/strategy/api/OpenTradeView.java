package dev.forexsignals.strategy.api;

import java.time.Instant;
import java.util.Map;

/**
 * Read-only view of an ACTIVE trade, given to the strategy at each entry-timeframe close so it can manage exits.
 * Prices are bid for longs and ask for shorts (the side the position closes on). bestPriceSinceEntry is the highest
 * bid (long) or lowest ask (short) since the fill; mfeR is the best excursion in R so far.
 */
public record OpenTradeView(Direction direction, Instant signalAt, Instant filledAt, double reference, double fill,
                            double initialStop, double currentStop, double rUnit, int barsSinceEntry,
                            double bestPriceSinceEntry, double mfeR, Map<String, Double> memo) {}
