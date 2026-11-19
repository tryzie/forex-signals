package dev.forexsignals.time;

import java.time.Instant;

/** Simulated time for replay/backtest. Advanced by event timestamps; never moves backwards. */
public final class SimulatedClock implements EngineClock {
    private Instant now;

    public SimulatedClock(Instant start) { this.now = start; }

    @Override
    public synchronized Instant now() { return now; }

    /** Moves the clock forward to {@code t}; earlier values are ignored (time never goes back). */
    public synchronized void advanceTo(Instant t) {
        if (t.isAfter(now)) now = t;
    }
}
