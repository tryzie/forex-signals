package dev.forexsignals.market;

import java.time.Instant;

/**
 * When a symbol's market is scheduled to be open. Implemented by the default profiles ({@link TradingHours})
 * and by the broker's own weekly schedule from the symbol spec ({@link TradingSchedule}), which wins when known.
 * Used by the stale-feed detector and the gap report.
 */
public interface MarketHours {

    boolean isOpen(Instant t);

    /** Inside the daily rollover window (16:58-17:05 New York), where short quote pauses are normal. */
    boolean inRolloverWindow(Instant t);
}
