package dev.forexsignals.history;

import dev.forexsignals.market.SessionCalendar;
import dev.forexsignals.market.SymbolCatalog.SymbolDef;
import dev.forexsignals.market.MarketHours;
import dev.forexsignals.market.TradingHours;
import dev.forexsignals.store.MarketStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gap report over candle_m1 (PLAN §14 Phase 1): every run of missing minutes between two consecutive bars.
 *
 * Classification, using the symbol's trading-hours profile:
 *  - expected   no missing minute falls in scheduled market hours (WEEKEND, DAILY_BREAK), or every
 *               missing open minute is inside the 16:58-17:05 NY rollover window (ROLLOVER);
 *  - unexpected otherwise. Reason NO_TICKS (short, typical of quiet markets) or LONG_OUTAGE_OR_HOLIDAY
 *               (6 h or more of open-market time missing: a feed outage or a market holiday to check by hand).
 * Unexpected gaps with at least {@code listMinOpenMinutes} open minutes are listed one by one and stored in
 * gap_report; shorter ones are counted in the summary. Expected gaps are summarised by reason.
 */
public final class GapReport {

    public record Gap(Instant start, Instant end, long minutes, long openMinutes, boolean expected, String reason) {}

    public record SymbolResult(String symbol, long bars, Instant first, Instant last, List<Gap> listed,
                               Map<String, long[]> expectedSummary, long shortUnexpectedCount, long shortUnexpectedMinutes) {}

    private GapReport() {}

    /** Pure classification of the gap between two consecutive bar open times (prev, next). */
    public static Gap classify(Instant prevOpen, Instant nextOpen, MarketHours hours) {
        Instant start = prevOpen.plusSeconds(60);
        long minutes = Duration.between(start, nextOpen).toMinutes();
        long open = 0, openOutsideRollover = 0;
        boolean saturday = false;
        for (Instant m = start; m.isBefore(nextOpen); m = m.plusSeconds(60)) {
            if (m.atZone(TradingHours.NEW_YORK).getDayOfWeek() == java.time.DayOfWeek.SATURDAY) saturday = true;
            if (hours.isOpen(m)) {
                open++;
                if (!hours.inRolloverWindow(m)) openOutsideRollover++;
            }
        }
        if (open == 0) return new Gap(start, nextOpen, minutes, 0, true, saturday ? "WEEKEND" : "DAILY_BREAK");
        if (openOutsideRollover == 0) return new Gap(start, nextOpen, minutes, open, true, "ROLLOVER");
        return new Gap(start, nextOpen, minutes, open, false, open >= 360 ? "LONG_OUTAGE_OR_HOLIDAY" : "NO_TICKS");
    }

    public static SymbolResult run(MarketStore store, String feed, SymbolDef sym, Instant runAt, int listMinOpenMinutes) {
        List<Gap> listed = new ArrayList<>();
        Map<String, long[]> expected = new LinkedHashMap<>(); // reason -> {count, minutes}
        long[] shortUnexpected = {0, 0};
        long[] bars = {0};
        Instant[] prev = {null}, first = {null};
        store.streamM1(feed, sym.code(), Instant.EPOCH, Instant.parse("9999-01-01T00:00:00Z"), c -> {
            bars[0]++;
            if (first[0] == null) first[0] = c.openTime();
            if (prev[0] != null && Duration.between(prev[0], c.openTime()).toMinutes() > 1) {
                Gap g = classify(prev[0], c.openTime(), sym.hours());
                if (g.expected()) {
                    long[] agg = expected.computeIfAbsent(g.reason(), k -> new long[2]);
                    agg[0]++;
                    agg[1] += g.minutes();
                } else if (g.openMinutes() >= listMinOpenMinutes) {
                    listed.add(g);
                } else {
                    shortUnexpected[0]++;
                    shortUnexpected[1] += g.openMinutes();
                }
            }
            prev[0] = c.openTime();
        });
        store.insertGaps(runAt, feed, sym.code(), listed);
        return new SymbolResult(sym.code(), bars[0], first[0], prev[0], listed, expected, shortUnexpected[0], shortUnexpected[1]);
    }

    /** Markdown report: summary table per symbol, then every listed unexpected gap with UTC, EAT and server time. */
    public static Path write(Path dir, String feed, Instant runAt, List<SymbolResult> results, int listMin) throws IOException {
        Files.createDirectories(dir);
        StringBuilder md = new StringBuilder();
        md.append("# Gap report: feed ").append(feed).append("\n\nRun at ").append(runAt).append(" UTC. Unexpected gaps with at least ")
                .append(listMin).append(" open-market minutes are listed; shorter ones are counted.\n\n");
        md.append("| Symbol | M1 bars | First | Last | Expected gaps (count / minutes by reason) | Listed unexpected | Short unexpected (count / open min) |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (SymbolResult r : results) {
            StringBuilder exp = new StringBuilder();
            r.expectedSummary().forEach((k, v) -> exp.append(k).append(' ').append(v[0]).append(" / ").append(v[1]).append("<br>"));
            md.append("| ").append(r.symbol()).append(" | ").append(r.bars()).append(" | ").append(r.first()).append(" | ").append(r.last())
                    .append(" | ").append(exp.isEmpty() ? "none" : exp).append(" | ").append(r.listed().size())
                    .append(" | ").append(r.shortUnexpectedCount()).append(" / ").append(r.shortUnexpectedMinutes()).append(" |\n");
        }
        for (SymbolResult r : results) {
            if (r.listed().isEmpty()) continue;
            md.append("\n## ").append(r.symbol()).append(": unexpected gaps\n\n");
            md.append("| Start UTC | Start EAT | Start server | Minutes | Open minutes | Reason |\n|---|---|---|---|---|---|\n");
            for (Gap g : r.listed()) {
                md.append("| ").append(g.start()).append(" | ").append(SessionCalendar.eat(g.start())).append(" | ")
                        .append(SessionCalendar.server(g.start())).append(" | ").append(g.minutes()).append(" | ")
                        .append(g.openMinutes()).append(" | ").append(g.reason()).append(" |\n");
            }
        }
        Path out = dir.resolve("gapreport-" + feed + "-" + runAt.toString().replace(':', '-') + ".md");
        Files.writeString(out, md);
        return out;
    }
}
