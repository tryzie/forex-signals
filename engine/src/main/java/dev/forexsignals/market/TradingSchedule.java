package dev.forexsignals.market;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A broker's weekly trading schedule (cTrader ProtoOASymbol.schedule): intervals in seconds from Sunday 00:00
 * in the schedule's time zone, start inclusive, end exclusive. Holidays are not included (the spec only
 * carries a separate holiday list, not read yet), so holiday gaps still show as unexpected in the gap report.
 */
public record TradingSchedule(ZoneId zone, List<int[]> intervals) implements MarketHours {

    private static final int WEEK = 7 * 24 * 3600;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime ROLLOVER_START = LocalTime.of(16, 58), ROLLOVER_END = LocalTime.of(17, 5);

    /** Builds a schedule from the stored spec map {timeZone, intervals:[{startSecond,endSecond}]}; empty if absent. */
    @SuppressWarnings("unchecked")
    public static Optional<TradingSchedule> fromSpec(Map<String, Object> tradingHours) {
        if (tradingHours == null) return Optional.empty();
        Object tz = tradingHours.get("timeZone"), iv = tradingHours.get("intervals");
        if (!(tz instanceof String zoneName) || zoneName.isBlank() || !(iv instanceof List<?> list) || list.isEmpty()) return Optional.empty();
        List<int[]> out = new ArrayList<>();
        for (Object o : list) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.add(new int[]{((Number) m.get("startSecond")).intValue(), ((Number) m.get("endSecond")).intValue()});
        }
        return Optional.of(new TradingSchedule(ZoneId.of(zoneName), List.copyOf(out)));
    }

    @Override
    public boolean isOpen(Instant t) {
        ZonedDateTime z = t.atZone(zone);
        int day = z.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : z.getDayOfWeek().getValue();
        int sec = day * 86_400 + z.toLocalTime().toSecondOfDay();
        for (int[] i : intervals) {
            if ((sec >= i[0] && sec < i[1]) || (sec + WEEK >= i[0] && sec + WEEK < i[1])) return true;
        }
        return false;
    }

    @Override
    public boolean inRolloverWindow(Instant t) {
        LocalTime lt = t.atZone(NEW_YORK).toLocalTime();
        return !lt.isBefore(ROLLOVER_START) && lt.isBefore(ROLLOVER_END);
    }
}
