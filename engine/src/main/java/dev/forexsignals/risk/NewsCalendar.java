package dev.forexsignals.risk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * News blackout (PLAN §13, §14.4). Phase 2 uses the reviewed MAJOR-events CSV (config/news.csv): +/-30 min around each
 * event (MAJOR tier), for every symbol containing the event's currency (metals and USDX count as USD). Only MAJOR events
 * are in the file: standard high-impact events are NOT filtered, and reports say so.
 * Outside the file's coverage the calendar is stale and the fail-safe fallback windows apply (Mon-Fri 08:00-10:30 New
 * York for all symbols, plus 07:00-09:30 London for EUR and GBP pairs), reported as NEWS_BLACKOUT_FALLBACK.
 */
public final class NewsCalendar {

    public record Event(Instant ts, String currency, String title, String tier, String source, String timeBasis) {}

    public enum Status { CLEAR, BLACKOUT, FALLBACK_BLACKOUT }

    private final List<Event> events;
    private final Instant coverFrom, coverTo;

    public NewsCalendar(List<Event> events, Instant coverFrom, Instant coverTo) {
        this.events = List.copyOf(events);
        this.coverFrom = coverFrom;
        this.coverTo = coverTo;
    }

    /** Loads the CSV; coverage = from the first event's month start to the last event's month end (UTC). */
    public static NewsCalendar load(Path csv) throws IOException {
        List<Event> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(csv);
        if (lines.isEmpty() || !lines.getFirst().startsWith("ts_utc,currency,title,tier")) throw new IOException(csv + ": unexpected header");
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            List<String> f = parseCsv(line);
            out.add(new Event(Instant.parse(f.get(0)), f.get(1), f.get(2), f.get(3), f.size() > 4 ? f.get(4) : "", f.size() > 5 ? f.get(5) : ""));
        }
        out.sort((a, b) -> a.ts().compareTo(b.ts()));
        if (out.isEmpty()) throw new IOException(csv + " has no events");
        ZonedDateTime first = out.getFirst().ts().atZone(ZoneOffset.UTC), last = out.getLast().ts().atZone(ZoneOffset.UTC);
        Instant from = first.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = last.withDayOfMonth(1).plusMonths(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return new NewsCalendar(out, from, to);
    }

    static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') q = false;
                else cur.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    public List<Event> events() { return events; }

    public Instant coverFrom() { return coverFrom; }

    public Instant coverTo() { return coverTo; }

    /** Currencies of a symbol that news can hit; metals and USDX count as USD. */
    public static Set<String> currencies(String base, String quote) {
        Set<String> s = new java.util.HashSet<>();
        for (String c : new String[]{base, quote}) s.add(c.equals("XAU") || c.equals("XAG") || c.equals("IDX") ? "USD" : c);
        return s;
    }

    public Status status(Instant t, Set<String> currencies) {
        if (t.isBefore(coverFrom) || !t.isBefore(coverTo)) return fallback(t, currencies) ? Status.FALLBACK_BLACKOUT : Status.CLEAR;
        for (Event e : events) {
            if (!currencies.contains(e.currency())) continue;
            Duration w = e.tier().equals("MAJOR") ? Duration.ofMinutes(30) : Duration.ofMinutes(15);
            if (!t.isBefore(e.ts().minus(w)) && !t.isAfter(e.ts().plus(w))) return Status.BLACKOUT;
            if (e.ts().isAfter(t.plus(Duration.ofHours(1)))) break;
        }
        return Status.CLEAR;
    }

    private static boolean fallback(Instant t, Set<String> ccy) {
        if (inWindow(t, ZoneId.of("America/New_York"), LocalTime.of(8, 0), LocalTime.of(10, 30))) return true;
        return (ccy.contains("EUR") || ccy.contains("GBP")) && inWindow(t, ZoneId.of("Europe/London"), LocalTime.of(7, 0), LocalTime.of(9, 30));
    }

    private static boolean inWindow(Instant t, ZoneId z, LocalTime from, LocalTime to) {
        ZonedDateTime d = t.atZone(z);
        if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) return false;
        return !d.toLocalTime().isBefore(from) && d.toLocalTime().isBefore(to);
    }
}
