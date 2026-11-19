package dev.forexsignals.feed;

import dev.forexsignals.market.SymbolCatalog.SymbolDef;
import dev.forexsignals.market.Tick;
import dev.forexsignals.market.TradingHours;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic synthetic quotes (CLAUDE.md rule 9: synthetic or demo data only). A seeded random walk
 * per symbol, with a spread that widens in the rollover window, ticks only while the symbol's market is
 * open, and occasional quiet stretches so gaps occur. Prices are rounded to the symbol's digits.
 *
 * Two uses: {@link #generate} produces a reproducible tick history for tests and replay; {@link #start}
 * streams the same kind of ticks in real time for a live dry run without any broker.
 * The numbers mean nothing about real markets and are never used to judge a strategy.
 */
public final class SyntheticFeed implements FeedAdapter {

    private static final Logger log = LoggerFactory.getLogger(SyntheticFeed.class);
    private static final Map<String, Double> BASE = Map.of("EURUSD", 1.08, "GBPUSD", 1.27, "USDJPY", 150.0, "AUDUSD", 0.66,
            "XAUUSD", 2300.0, "XAGUSD", 27.0, "USDCAD", 1.36, "USDSEK", 10.5, "USDCHF", 0.88, "USDX", 104.0);

    private final long seed;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    public SyntheticFeed(long seed) { this.seed = seed; }

    @Override
    public String name() { return "synthetic"; }

    @Override
    public Map<String, SymbolSpec> loadSymbols(List<SymbolDef> wanted) {
        Map<String, SymbolSpec> out = new HashMap<>();
        for (SymbolDef d : wanted) {
            double contract = switch (d.code()) { case "XAUUSD" -> 100.0; case "XAGUSD" -> 5000.0; default -> 100_000.0; };
            out.put(d.code(), new SymbolSpec(d.code(), name(), d.code(), null, d.digits(), d.digits() - 1, contract, 0.01, 0.01, 100.0,
                    Map.of(), null, null, Map.of("profile", String.valueOf(d.hours())), Map.of("synthetic", true)));
        }
        return out;
    }

    /** One symbol's random walk state. */
    private static final class Walk {
        final SymbolDef def;
        final Random rnd;
        double mid;

        Walk(SymbolDef def, long seed) {
            this.def = def;
            this.rnd = new Random(seed ^ def.code().hashCode());
            this.mid = BASE.getOrDefault(def.code(), 1.0);
        }

        /** Next quote at time t, or null if the market is closed then. */
        Tick next(String feed, Instant t, long seq) {
            if (!def.hours().isOpen(t)) return null;
            double point = def.pointSize();
            mid += rnd.nextGaussian() * mid * 0.00002;
            double spreadPoints = Math.max(1, Math.round(mid * 0.00001 / point * (1 + rnd.nextDouble())));
            if (TradingHours.FX.inRolloverWindow(t)) spreadPoints *= 4;
            double bid = Math.round((mid - spreadPoints * point / 2) / point) * point;
            double ask = Math.round((bid + spreadPoints * point) / point) * point;
            return new Tick(feed, def.code(), t, round(bid, def.digits()), round(ask, def.digits()), t, seq);
        }
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    /**
     * Reproducible tick history for [from, to): per symbol, inter-arrival times of 0.2-8 s, plus about one
     * 3-7 minute quiet stretch per day per symbol. Returned in (ts, seq) order across symbols.
     */
    public List<Tick> generate(List<SymbolDef> symbols, Instant from, Instant to) {
        List<Tick> all = new ArrayList<>();
        long seq = 0;
        for (SymbolDef d : symbols) {
            Walk w = new Walk(d, seed);
            Instant t = from;
            while (true) {
                t = t.plusMillis(200 + w.rnd.nextInt(7800));
                if (w.rnd.nextInt(5000) == 0) t = t.plusSeconds(180 + w.rnd.nextInt(240));
                if (!t.isBefore(to)) break;
                Tick k = w.next(name(), t, 0);
                if (k != null) all.add(k);
            }
        }
        all.sort((a, b) -> a.ts().compareTo(b.ts()) != 0 ? a.ts().compareTo(b.ts()) : a.symbol().compareTo(b.symbol()));
        List<Tick> out = new ArrayList<>(all.size());
        for (Tick k : all) out.add(new Tick(k.feed(), k.symbol(), k.ts(), k.bid(), k.ask(), k.recvTs(), seq++));
        return out;
    }

    @Override
    public void start(List<SymbolDef> symbols, Map<String, SymbolSpec> specs, FeedListener listener) {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("already started");
        List<Walk> walks = symbols.stream().map(d -> new Walk(d, seed)).toList();
        thread = Thread.ofVirtual().name("synthetic-feed").start(() -> {
            listener.onStatus(FeedListener.Status.CONNECTED, "synthetic");
            long seq = 0;
            Random pace = new Random(seed);
            while (running.get()) {
                try {
                    Thread.sleep(Duration.ofMillis(100 + pace.nextInt(400)));
                } catch (InterruptedException e) {
                    break;
                }
                Instant now = Instant.now();
                for (Walk w : walks) {
                    if (pace.nextInt(3) != 0) continue;
                    Tick k = w.next(name(), now, seq++);
                    if (k != null) listener.onTick(k);
                }
            }
            listener.onStatus(FeedListener.Status.DISCONNECTED, "synthetic stopped");
        });
        log.info("synthetic feed started for {} symbols (seed {})", symbols.size(), seed);
    }

    @Override
    public void close() {
        running.set(false);
        if (thread != null) thread.interrupt();
    }
}
