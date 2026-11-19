package dev.forexsignals.history;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Spaces request starts at least 1/maxPerSecond apart (4/s by default, under cTrader's 5/s history limit).
 * Time and sleeping are injectable so tests can check the pacing without waiting.
 */
public final class Pacer {

    public interface Sleeper { void sleep(Duration d) throws InterruptedException; }

    private final Duration minGap;
    private final Supplier<Instant> clock;
    private final Sleeper sleeper;
    private Instant last;
    private long acquired;

    /** Number of requests started through this pacer. */
    public synchronized long acquired() { return acquired; }

    public Pacer(double maxPerSecond, Supplier<Instant> clock, Sleeper sleeper) {
        if (maxPerSecond <= 0) throw new IllegalArgumentException("maxPerSecond must be > 0");
        this.minGap = Duration.ofNanos((long) Math.ceil(1_000_000_000L / maxPerSecond));
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public static Pacer realTime(double maxPerSecond) {
        return new Pacer(maxPerSecond, Instant::now, d -> Thread.sleep(d));
    }

    /** Blocks until the next request may start, then records it. */
    public synchronized void acquire() throws InterruptedException {
        Instant now = clock.get();
        if (last != null) {
            Instant earliest = last.plus(minGap);
            if (now.isBefore(earliest)) {
                sleeper.sleep(Duration.between(now, earliest));
                now = earliest.isAfter(clock.get()) ? earliest : clock.get();
            }
        }
        last = now;
        acquired++;
    }
}
