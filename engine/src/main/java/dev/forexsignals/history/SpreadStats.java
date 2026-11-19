package dev.forexsignals.history;

import dev.forexsignals.market.SessionCalendar;
import dev.forexsignals.market.SymbolCatalog.SymbolDef;
import dev.forexsignals.store.MarketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Spread statistics from recorded ticks (PLAN §6): median and p95 per symbol per session bucket
 * (ASIA, LONDON, OVERLAP, NEW_YORK, ROLLOVER) over a window, upserted into spread_stats.
 * Spreads are counted in whole points in a histogram, so the median and p95 are exact and memory stays
 * small however many ticks there are. Only ticks with both bid and ask count.
 */
public final class SpreadStats {

    private static final Logger log = LoggerFactory.getLogger(SpreadStats.class);

    private SpreadStats() {}

    public record Stat(String bucket, double median, double p95, long samples) {}

    public static Map<String, Stat> compute(MarketStore store, String feed, SymbolDef sym, Instant from, Instant to) {
        double point = sym.pointSize();
        Map<SessionCalendar.Bucket, TreeMap<Long, Long>> hist = new EnumMap<>(SessionCalendar.Bucket.class);
        store.streamTicks(feed, List.of(sym.code()), from, to, t -> {
            double sp = t.spread();
            if (Double.isNaN(sp)) return;
            hist.computeIfAbsent(SessionCalendar.bucket(t.ts()), k -> new TreeMap<>()).merge(Math.round(sp / point), 1L, Long::sum);
        });
        Map<String, Stat> out = new TreeMap<>();
        hist.forEach((bucket, h) -> {
            long n = h.values().stream().mapToLong(Long::longValue).sum();
            Stat s = new Stat(bucket.name(), quantile(h, n, 0.5) * point, quantile(h, n, 0.95) * point, n);
            out.put(bucket.name(), s);
            store.upsertSpreadStat(feed, sym.code(), bucket.name(), from, to, s.median(), s.p95(), n);
        });
        log.info("spread stats {} {} {} .. {}: {}", feed, sym.code(), from, to, out);
        return out;
    }

    /** Nearest-rank quantile of a histogram (value -> count). */
    static long quantile(TreeMap<Long, Long> h, long n, double q) {
        long rank = Math.max(1, (long) Math.ceil(q * n));
        long seen = 0;
        for (var e : h.entrySet()) {
            seen += e.getValue();
            if (seen >= rank) return e.getKey();
        }
        return h.isEmpty() ? 0 : h.lastKey();
    }
}
