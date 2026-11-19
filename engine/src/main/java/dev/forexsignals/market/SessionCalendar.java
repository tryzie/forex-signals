package dev.forexsignals.market;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Trading sessions in IANA zones (CLAUDE.md, PLAN §5). All inputs and outputs are UTC instants;
 * display helpers render EAT (Africa/Nairobi) and broker server time (New York + 7 h, i.e. UTC+2/+3).
 *
 *  London     08:00-17:00 Europe/London, Monday-Friday
 *  New York   08:00-17:00 America/New_York, Monday-Friday
 *  Entries    allowed when either session is open, the FX market is open, and it is not after the
 *             Friday cut-off (15:00 New York, two hours before the 17:00 close).
 *  Buckets    for spread statistics: ROLLOVER (16:55-17:10 NY) wins, then OVERLAP (both open),
 *             LONDON, NEW_YORK, otherwise ASIA.
 */
public final class SessionCalendar {

    public static final ZoneId LONDON = ZoneId.of("Europe/London");
    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    public static final ZoneId EAT = ZoneId.of("Africa/Nairobi");

    private static final LocalTime SESSION_OPEN = LocalTime.of(8, 0);
    private static final LocalTime SESSION_CLOSE = LocalTime.of(17, 0);
    private static final LocalTime FRIDAY_CUTOFF_NY = LocalTime.of(15, 0);
    private static final LocalTime ROLLOVER_START = LocalTime.of(16, 55);
    private static final LocalTime ROLLOVER_END = LocalTime.of(17, 10);
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public enum Bucket { ASIA, LONDON, OVERLAP, NEW_YORK, ROLLOVER }

    private SessionCalendar() {}

    public static boolean londonOpen(Instant t) { return sessionOpen(t, LONDON); }

    public static boolean newYorkOpen(Instant t) { return sessionOpen(t, NEW_YORK); }

    private static boolean sessionOpen(Instant t, ZoneId zone) {
        ZonedDateTime z = t.atZone(zone);
        DayOfWeek d = z.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return false;
        LocalTime lt = z.toLocalTime();
        return !lt.isBefore(SESSION_OPEN) && lt.isBefore(SESSION_CLOSE);
    }

    /** True after 15:00 New York on Friday (no new entries in the last two hours before the close). */
    public static boolean afterFridayCutoff(Instant t) {
        ZonedDateTime ny = t.atZone(NEW_YORK);
        return ny.getDayOfWeek() == DayOfWeek.FRIDAY && !ny.toLocalTime().isBefore(FRIDAY_CUTOFF_NY);
    }

    /** Session gate for new entries (the risk gate's OUTSIDE_SESSION check uses this). */
    public static boolean entryAllowed(Instant t) {
        return TradingHours.FX.isOpen(t) && (londonOpen(t) || newYorkOpen(t)) && !afterFridayCutoff(t);
    }

    public static Bucket bucket(Instant t) {
        LocalTime ny = t.atZone(NEW_YORK).toLocalTime();
        if (!ny.isBefore(ROLLOVER_START) && ny.isBefore(ROLLOVER_END)) return Bucket.ROLLOVER;
        boolean lon = londonOpen(t), nyo = newYorkOpen(t);
        if (lon && nyo) return Bucket.OVERLAP;
        if (lon) return Bucket.LONDON;
        if (nyo) return Bucket.NEW_YORK;
        return Bucket.ASIA;
    }

    /** "yyyy-MM-dd HH:mm" in EAT, for the UI, Telegram and reports. */
    public static String eat(Instant t) { return DISPLAY.format(t.atZone(EAT)); }

    /** Broker server time: New York wall clock + 7 hours (UTC+2 in northern winter, UTC+3 in summer). */
    public static LocalDateTime serverTime(Instant t) { return t.atZone(NEW_YORK).toLocalDateTime().plusHours(7); }

    public static String server(Instant t) { return DISPLAY.format(serverTime(t)); }
}
