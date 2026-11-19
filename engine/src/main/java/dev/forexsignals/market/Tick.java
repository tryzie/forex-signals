package dev.forexsignals.market;

import java.time.Instant;

/**
 * One quote. {@code ask} is NaN when the feed provides no ask (spread then unknown).
 * {@code ts} is the feed's timestamp; {@code recvTs} is when we received it; {@code seq} is the arrival
 * order within one process run (used only to break ties deterministically).
 */
public record Tick(String feed, String symbol, Instant ts, double bid, double ask, Instant recvTs, long seq) {
    public boolean hasAsk() { return !Double.isNaN(ask); }

    public double spread() { return hasAsk() ? ask - bid : Double.NaN; }
}
