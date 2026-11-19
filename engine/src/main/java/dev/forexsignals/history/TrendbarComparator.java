package dev.forexsignals.history;

import dev.forexsignals.market.Candle;
import dev.forexsignals.market.SessionCalendar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Phase 1 acceptance check: candles built from replayed ticks vs the feed's own candles (cTrader
 * trendbars) for the same period. OHLC must match within 0 points; tick-less periods must be absent
 * in both. Every difference is reported; only differences with a known, stated cause count as explained.
 *
 * Explained causes:
 *  - RANGE_EDGE   the first or last bar of the compared window, which one side may only see partly.
 * Anything else (OHLC difference, bar only on one side) is UNEXPLAINED. The acceptance target is 0.
 */
public final class TrendbarComparator {

    public record Diff(Instant openTime, String kind, String detail, boolean explained) {}

    public record Report(String feed, String symbol, String timeframe, Instant from, Instant to, int built, int reference,
                         int matched, List<Diff> diffs) {
        public long unexplained() { return diffs.stream().filter(d -> !d.explained()).count(); }
    }

    private TrendbarComparator() {}

    public static Report compare(String feed, String symbol, String tf, Instant from, Instant to,
                                 List<Candle> built, List<Candle> reference, double pointSize) {
        Map<Instant, Candle> b = new TreeMap<>(), r = new TreeMap<>();
        built.forEach(c -> b.put(c.openTime(), c));
        reference.forEach(c -> r.put(c.openTime(), c));
        TreeMap<Instant, Boolean> all = new TreeMap<>();
        b.keySet().forEach(k -> all.put(k, true));
        r.keySet().forEach(k -> all.put(k, true));
        Instant firstKey = all.isEmpty() ? null : all.firstKey(), lastKey = all.isEmpty() ? null : all.lastKey();

        List<Diff> diffs = new ArrayList<>();
        int matched = 0;
        for (Instant t : all.keySet()) {
            Candle cb = b.get(t), cr = r.get(t);
            boolean edge = t.equals(firstKey) || t.equals(lastKey);
            if (cb == null) {
                diffs.add(new Diff(t, "ONLY_REFERENCE", "reference bar has no built counterpart", edge));
                continue;
            }
            if (cr == null) {
                diffs.add(new Diff(t, "ONLY_BUILT", "built bar missing from reference", edge));
                continue;
            }
            long dO = points(cb.open(), cr.open(), pointSize), dH = points(cb.high(), cr.high(), pointSize),
                    dL = points(cb.low(), cr.low(), pointSize), dC = points(cb.close(), cr.close(), pointSize);
            if (dO == 0 && dH == 0 && dL == 0 && dC == 0) {
                matched++;
            } else {
                diffs.add(new Diff(t, "OHLC", "diff in points O=" + dO + " H=" + dH + " L=" + dL + " C=" + dC, edge));
            }
        }
        return new Report(feed, symbol, tf, from, to, b.size(), r.size(), matched, diffs);
    }

    /** Difference in whole points (rounded), so binary floating-point noise never counts as a mismatch. */
    static long points(double a, double b, double pointSize) {
        return Math.round(a / pointSize) - Math.round(b / pointSize);
    }

    public static Path write(Path dir, Instant runAt, List<Report> reports, String note) throws IOException {
        Files.createDirectories(dir);
        StringBuilder md = new StringBuilder("# Built candles vs feed candles\n\nRun at " + runAt + " UTC. " + note + "\n\n"
                + "| Feed | Symbol | TF | From | To | Built | Reference | Matched | Diffs | Unexplained |\n|---|---|---|---|---|---|---|---|---|---|\n");
        for (Report r : reports) {
            md.append("| ").append(r.feed()).append(" | ").append(r.symbol()).append(" | ").append(r.timeframe()).append(" | ")
                    .append(r.from()).append(" | ").append(r.to()).append(" | ").append(r.built()).append(" | ").append(r.reference())
                    .append(" | ").append(r.matched()).append(" | ").append(r.diffs().size()).append(" | ").append(r.unexplained()).append(" |\n");
        }
        for (Report r : reports) {
            if (r.diffs().isEmpty()) continue;
            md.append("\n## ").append(r.symbol()).append(" ").append(r.timeframe()).append("\n\n| Open UTC | Open server | Kind | Detail | Explained |\n|---|---|---|---|---|\n");
            for (Diff d : r.diffs()) {
                md.append("| ").append(d.openTime()).append(" | ").append(SessionCalendar.server(d.openTime())).append(" | ").append(d.kind())
                        .append(" | ").append(d.detail()).append(" | ").append(d.explained() ? "yes (RANGE_EDGE)" : "**no**").append(" |\n");
            }
        }
        Path out = dir.resolve("m5check-" + runAt.toString().replace(':', '-') + ".md");
        Files.writeString(out, md);
        return out;
    }
}
