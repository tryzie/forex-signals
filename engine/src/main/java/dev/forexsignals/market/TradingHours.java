package dev.forexsignals.market;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Default weekly trading-hours profiles, in New York local time so they follow daylight saving.
 * Used by the stale-feed detector and the gap report until the feed's own spec hours are available
 * (cTrader spec hours replace these in 1b). Holidays are not modelled: a holiday gap shows up as
 * "unexpected" in the gap report and is explained by hand.
 *
 *  FX     Sunday 17:00 to Friday 17:00 NY, continuous. 16:58-17:05 NY daily is a rollover window where
 *         short gaps are expected.
 *  METAL  Sunday 18:00 to Friday 17:00 NY, with a daily break 17:00-18:00 NY.
 */
public enum TradingHours implements MarketHours {
    FX(LocalTime.of(17, 0), false),
    METAL(LocalTime.of(18, 0), true);

    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime CLOSE = LocalTime.of(17, 0);
    private static final LocalTime ROLLOVER_START = LocalTime.of(16, 58);
    private static final LocalTime ROLLOVER_END = LocalTime.of(17, 5);

    private final LocalTime sundayOpen;
    private final boolean dailyBreak;

    TradingHours(LocalTime sundayOpen, boolean dailyBreak) {
        this.sundayOpen = sundayOpen;
        this.dailyBreak = dailyBreak;
    }

    /** True when the market is scheduled to be open at this instant. */
    @Override
    public boolean isOpen(Instant t) {
        ZonedDateTime ny = t.atZone(NEW_YORK);
        DayOfWeek d = ny.getDayOfWeek();
        LocalTime lt = ny.toLocalTime();
        if (d == DayOfWeek.SATURDAY) return false;
        if (d == DayOfWeek.SUNDAY) return !lt.isBefore(sundayOpen);
        if (d == DayOfWeek.FRIDAY && !lt.isBefore(CLOSE)) return false;
        if (dailyBreak && !lt.isBefore(CLOSE) && lt.isBefore(sundayOpen)) return false;
        return true;
    }

    /** True inside the daily rollover window, where short quote gaps are normal. */
    @Override
    public boolean inRolloverWindow(Instant t) {
        LocalTime lt = t.atZone(NEW_YORK).toLocalTime();
        return !lt.isBefore(ROLLOVER_START) && lt.isBefore(ROLLOVER_END);
    }
}
