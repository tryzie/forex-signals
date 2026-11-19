package dev.forexsignals.feed;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Exponential backoff with full jitter: attempt n waits a random time in [base/2, min(cap, 1 s * 2^n)]. */
public final class Backoff {
    private final Duration cap;
    private int attempt;

    public Backoff(Duration cap) { this.cap = cap; }

    public Duration next() {
        long ceiling = Math.min(cap.toMillis(), 1000L << Math.min(attempt, 16));
        attempt++;
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1));
    }

    public void reset() { attempt = 0; }
}
