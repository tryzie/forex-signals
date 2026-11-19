package dev.forexsignals.feed;

import dev.forexsignals.market.SymbolCatalog.SymbolDef;
import dev.forexsignals.market.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Feed health (PLAN §4), Phase 1 = log and feed_event rows only (Telegram arrives in Phase 4).
 *
 *  - STALE: no tick for staleSec while the symbol's market is scheduled to be open (so weekends and the
 *    metals' daily break never alert). Emitted once per stale episode; RECOVERED on the next tick.
 *  - CONNECTED / DISCONNECTED: passed through from the adapter.
 *  - Metrics, logged once a minute: ticks per minute per symbol, and tick lag (receive time minus feed
 *    timestamp) p50 / p95 / p99 over the minute.
 *
 * Used only on the engine loop thread.
 */
public final class FeedHealth {

    private static final Logger log = LoggerFactory.getLogger(FeedHealth.class);

    /** Where health events go (feed_event table + log). */
    public interface EventSink {
        void event(Instant ts, String feed, String symbol, String kind, Map<String, ?> detail);
    }

    private final String feed;
    private final Map<String, SymbolDef> symbols;
    private final Duration staleFx, staleMetal;
    private final EventSink sink;
    private final Map<String, Instant> lastTick = new HashMap<>();
    private final Set<String> stale = new HashSet<>();
    private final Instant startedAt;
    private boolean connected;

    // per-minute metrics
    private final Map<String, Integer> ticksThisMinute = new LinkedHashMap<>();
    private long[] lagMs = new long[1024];
    private int lagN;
    private Instant metricsWindowStart;

    public FeedHealth(String feed, Map<String, SymbolDef> symbols, Duration staleFx, Duration staleMetal, Instant startedAt, EventSink sink) {
        this.feed = feed;
        this.symbols = symbols;
        this.staleFx = staleFx;
        this.staleMetal = staleMetal;
        this.startedAt = startedAt;
        this.metricsWindowStart = startedAt;
        this.sink = sink;
    }

    public void onStatus(FeedListener.Status status, String detail, Instant now) {
        connected = status == FeedListener.Status.CONNECTED;
        sink.event(now, feed, null, status.name(), Map.of("detail", detail == null ? "" : detail));
        if (connected) log.info("feed {} connected: {}", feed, detail);
        else log.warn("feed {} disconnected: {}", feed, detail);
    }

    public boolean connected() { return connected; }

    public void onTick(Tick t) {
        lastTick.put(t.symbol(), t.recvTs());
        ticksThisMinute.merge(t.symbol(), 1, Integer::sum);
        if (lagN == lagMs.length) lagMs = Arrays.copyOf(lagMs, lagN * 2);
        lagMs[lagN++] = Duration.between(t.ts(), t.recvTs()).toMillis();
        if (stale.remove(t.symbol())) {
            sink.event(t.recvTs(), feed, t.symbol(), "RECOVERED", Map.of("tickTs", t.ts().toString()));
            log.info("feed {} {} recovered", feed, t.symbol());
        }
    }

    /** True if the symbol is currently stale (signal generation for it must pause; Phase 2+). */
    public boolean isStale(String symbol) { return stale.contains(symbol); }

    /** Call regularly (every few seconds of engine time). */
    public void check(Instant now) {
        for (SymbolDef d : symbols.values()) {
            if (!d.hours().isOpen(now)) continue;
            Duration limit = "METAL".equals(d.assetClass()) ? staleMetal : staleFx;
            Instant last = lastTick.getOrDefault(d.code(), startedAt);
            // A market that only just opened gets the full stale window from its open, not from the last Friday tick.
            if (!d.hours().isOpen(now.minus(limit))) continue;
            // The daily rollover pause (16:58-17:05 NY) is normal: no alert during it or within one window after it.
            if (d.hours().inRolloverWindow(now) || d.hours().inRolloverWindow(now.minus(limit))) continue;
            if (Duration.between(last, now).compareTo(limit) > 0 && stale.add(d.code())) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("lastTick", last.toString());
                detail.put("silentSec", Duration.between(last, now).toSeconds());
                detail.put("limitSec", limit.toSeconds());
                sink.event(now, feed, d.code(), "STALE", detail);
                log.warn("feed {} {} STALE: no tick for {} s while the market is open", feed, d.code(), Duration.between(last, now).toSeconds());
            }
        }
        if (Duration.between(metricsWindowStart, now).toSeconds() >= 60) logMetrics(now);
    }

    private void logMetrics(Instant now) {
        long[] lags = Arrays.copyOf(lagMs, lagN);
        Arrays.sort(lags);
        log.info("feed {} metrics: ticks/min {} lag ms p50={} p95={} p99={} stale={}", feed, ticksThisMinute,
                pct(lags, 0.50), pct(lags, 0.95), pct(lags, 0.99), stale);
        ticksThisMinute.clear();
        lagN = 0;
        metricsWindowStart = now;
    }

    static long pct(long[] sorted, double p) {
        if (sorted.length == 0) return -1;
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(p * sorted.length))];
    }
}
