package dev.forexsignals.market;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Candle timeframes and their alignment (PLAN §5, ADR 0004).
 *
 *  M1, M5, M15   aligned to UTC minutes (floor of epoch minutes).
 *  H1, H4, D1    aligned to the 17:00 New York trading day. H4 bars open at 17, 21, 01, 05, 09 and 13
 *                New York time, so they follow daylight saving like the broker's server time (UTC+2/+3).
 *
 * New York alignment works on the local wall clock: take the local time, find the 17:00 that starts its
 * trading day, floor the wall-clock minutes since then to the bar length, convert back. Converting back
 * prefers the offset of the timestamp itself, which picks the right instant in the repeated autumn hour.
 * (DST changes happen at 02:00 on Sunday, when FX is closed, so no live bar ever straddles one.)
 */
public enum Timeframe {
    M1(1, false), M5(5, false), M15(15, false), H1(60, true), H4(240, true), D1(1440, true);

    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime DAY_START = LocalTime.of(17, 0);

    private final int minutes;
    private final boolean newYorkAligned;

    Timeframe(int minutes, boolean newYorkAligned) {
        this.minutes = minutes;
        this.newYorkAligned = newYorkAligned;
    }

    public int minutes() { return minutes; }

    public Duration duration() { return Duration.ofMinutes(minutes); }

    /** Start of the bar that contains {@code t}. */
    public Instant bucketStart(Instant t) {
        if (!newYorkAligned) {
            long epochMin = Math.floorDiv(t.getEpochSecond(), 60);
            return Instant.ofEpochSecond((epochMin - Math.floorMod(epochMin, minutes)) * 60);
        }
        ZonedDateTime ny = t.atZone(NEW_YORK);
        LocalDateTime local = ny.toLocalDateTime();
        LocalDateTime dayStart = local.toLocalTime().isBefore(DAY_START)
                ? local.toLocalDate().minusDays(1).atTime(DAY_START)
                : local.toLocalDate().atTime(DAY_START);
        long sinceDayStart = Duration.between(dayStart, local).toMinutes();
        LocalDateTime startLocal = dayStart.plusMinutes(sinceDayStart - Math.floorMod(sinceDayStart, minutes));
        Instant start = ZonedDateTime.ofLocal(startLocal, NEW_YORK, ny.getOffset()).toInstant();
        // Safety net for the repeated autumn hour: a bar can never start after the instant it contains.
        if (start.isAfter(t)) start = ZonedDateTime.ofLocal(startLocal, NEW_YORK, null).toInstant();
        return start;
    }

    /**
     * End (exclusive) of the bar starting at {@code start}: the first instant after it that belongs to a
     * different bar. Normal days take the fast path (start + bar length on the wall clock). Around DST
     * changes the end is found by stepping minute by minute, so that bars always tile time exactly, e.g.
     * the repeated 01:00 hour in November is two separate H1 bars.
     */
    public Instant bucketEnd(Instant start) {
        if (!newYorkAligned) return start.plusSeconds(60L * minutes);
        ZonedDateTime s = start.atZone(NEW_YORK);
        Instant guess = ZonedDateTime.ofLocal(s.toLocalDateTime().plusMinutes(minutes), NEW_YORK, s.getOffset()).toInstant();
        if (guess.isAfter(start) && bucketStart(guess.minusSeconds(60)).equals(start) && !bucketStart(guess).equals(start)) {
            return guess;
        }
        Instant m = start.plusSeconds(60);
        while (bucketStart(m).equals(start)) m = m.plusSeconds(60);
        return m;
    }
}
