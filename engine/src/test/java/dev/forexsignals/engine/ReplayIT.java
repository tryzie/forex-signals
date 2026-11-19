package dev.forexsignals.engine;

import dev.forexsignals.feed.SyntheticFeed;
import dev.forexsignals.history.TrendbarComparator;
import dev.forexsignals.market.Candle;
import dev.forexsignals.market.PriceBasis;
import dev.forexsignals.market.SymbolCatalog;
import dev.forexsignals.market.SymbolCatalog.SymbolDef;
import dev.forexsignals.market.Tick;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.store.AsyncWriter;
import dev.forexsignals.store.Database;
import dev.forexsignals.store.MarketStore;
import dev.forexsignals.store.TestDb;
import dev.forexsignals.time.SimulatedClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One week of ticks: built "live" (with irregular clock steps, like the 500 ms engine timer) while being
 * recorded to Postgres through the async writer, then replayed from the database with the simulated clock.
 * The M5 candles of both runs must be identical (0 mismatches), as must every other timeframe.
 * The real Phase 1 check compares against cTrader trendbars (1b pending); this proves the recording and
 * replay path loses or reorders nothing.
 */
class ReplayIT {

    private static final Instant FROM = Instant.parse("2024-03-03T21:00:00Z"); // Sunday 16:00 EST, one hour before the weekly open
    private static final Instant TO = Instant.parse("2024-03-09T00:00:00Z");

    @Test
    void replayOfRecordedWeekRebuildsIdenticalCandles() throws Exception {
        SymbolCatalog cat = SymbolCatalog.load(Optional.empty());
        List<SymbolDef> symbols = List.of(cat.get("EURUSD"), cat.get("USDJPY"), cat.get("XAUUSD"));
        List<Tick> week = new SyntheticFeed(3).generate(symbols, FROM, TO);
        assertThat(week.size()).isGreaterThan(150_000);

        try (Database db = TestDb.freshMigrated()) {
            db.ensurePartitions(FROM, TO);
            MarketStore store = new MarketStore(db);

            // "Live": ticks arrive, the clock steps irregularly, ticks are recorded asynchronously.
            Map<Timeframe, List<Candle>> live = new HashMap<>();
            Random r = new Random(5);
            try (AsyncWriter<Tick> recorder = new AsyncWriter<>("ticks", 2000, Duration.ofMillis(200), store::insertTicks)) {
                MarketPipeline p = new MarketPipeline("synthetic", PriceBasis.BID, Duration.ofSeconds(2),
                        c -> live.computeIfAbsent(c.timeframe(), k -> new ArrayList<>()).add(c), null, recorder::add, null);
                for (Tick t : week) {
                    if (r.nextInt(4) == 0) p.onTime(t.ts().minusMillis(r.nextInt(500)));
                    p.onTick(t);
                }
                p.onTime(TO);
                p.finishAt(TO);
            }
            assertThat(store.scalarLong("SELECT count(*) FROM tick")).as("every tick recorded once").isEqualTo(week.size());

            // Replay from the database with the simulated clock (exactly what --mode=replay --source=db does).
            Map<Timeframe, List<Candle>> replay = new HashMap<>();
            SimulatedClock clock = new SimulatedClock(FROM);
            MarketPipeline p = new MarketPipeline("synthetic", PriceBasis.BID, Duration.ofSeconds(2),
                    c -> replay.computeIfAbsent(c.timeframe(), k -> new ArrayList<>()).add(c), null, null, null);
            long n = store.streamTicks("synthetic", symbols.stream().map(SymbolDef::code).toList(), FROM, TO, t -> {
                clock.advanceTo(t.ts());
                p.onTime(clock.now());
                p.onTick(t);
            });
            clock.advanceTo(TO);
            p.onTime(clock.now());
            p.finishAt(TO);
            assertThat(n).isEqualTo(week.size());

            // Spread calibration from the recorded ticks: every session bucket gets an exact median and p95.
            var stats = dev.forexsignals.history.SpreadStats.compute(store, "synthetic", symbols.getFirst(), FROM, TO);
            assertThat(stats).containsKeys("ASIA", "LONDON", "OVERLAP", "NEW_YORK", "ROLLOVER");
            // The synthetic feed widens spreads x4 in 16:58-17:05 NY, about half of the 16:55-17:10 bucket: it shows in the p95.
            assertThat(stats.get("ROLLOVER").p95()).as("rollover tail is wider").isGreaterThan(stats.get("LONDON").p95() * 1.5);
            assertThat(stats.get("LONDON").p95()).isGreaterThanOrEqualTo(stats.get("LONDON").median());
            assertThat(store.scalarLong("SELECT count(*) FROM spread_stats WHERE symbol = 'EURUSD'")).isEqualTo(5);

            for (SymbolDef d : symbols) {
                List<Candle> a = live.get(Timeframe.M5).stream().filter(c -> c.symbol().equals(d.code())).toList();
                List<Candle> b = replay.get(Timeframe.M5).stream().filter(c -> c.symbol().equals(d.code())).toList();
                TrendbarComparator.Report rep = TrendbarComparator.compare("synthetic", d.code(), "M5", FROM, TO, b, a, d.pointSize());
                assertThat(rep.diffs()).as(d.code() + " M5 diffs").isEmpty();
                assertThat(rep.matched()).isGreaterThan(1000);
            }
            // Same candles, field for field. Emission order across symbols may differ (live clock steps close
            // bars at other moments than replay), so compare in (symbol, open time) order.
            Comparator<Candle> order = Comparator.comparing(Candle::symbol).thenComparing(Candle::openTime);
            for (Timeframe tf : Timeframe.values()) {
                List<Candle> a = live.get(tf).stream().sorted(order).toList(), b = replay.get(tf).stream().sorted(order).toList();
                assertThat(b).as(tf + " count").hasSameSizeAs(a);
                assertThat(b).as(tf + " identical incl. spread stats and tick counts").isEqualTo(a);
            }
        }
    }
}
