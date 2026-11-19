package dev.forexsignals.time;

import java.time.Instant;

/** Real UTC time, for live mode. */
public final class WallClock implements EngineClock {
    @Override
    public Instant now() { return Instant.now(); }
}
