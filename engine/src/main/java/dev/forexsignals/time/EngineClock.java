package dev.forexsignals.time;

import java.time.Instant;

/**
 * The only time source the engine uses (CLAUDE.md rule 3: backtest, replay and live differ only in clock
 * and data source). Live uses {@link WallClock}; replay and backtest use {@link SimulatedClock}.
 */
public interface EngineClock {
    Instant now();
}
