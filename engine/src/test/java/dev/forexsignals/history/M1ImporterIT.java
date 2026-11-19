package dev.forexsignals.history;

import dev.forexsignals.market.Candle;
import dev.forexsignals.market.SymbolCatalog;
import dev.forexsignals.market.SymbolCatalog.SymbolDef;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.store.Database;
import dev.forexsignals.store.MarketStore;
import dev.forexsignals.store.TestDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Importer acceptance (PLAN §14 Phase 1): month chunks, paced, backoff on rate limits, and a kill
 * mid-run followed by a restart continues from import_progress with no duplicates (row-count test).
 * Uses synthetic history (two symbols, ~10 weeks spanning three calendar months and the March US DST change).
 */
class M1ImporterIT {

    private static final Instant FROM = Instant.parse("2024-02-12T00:00:00Z");
    private static final Instant TO = Instant.parse("2024-04-22T00:00:00Z");
    private static Database db;
    private static MarketStore store;
    private static List<SymbolDef> symbols;
    private static FakeHistorySource source;

    @BeforeAll
    static void setUp() {
        db = TestDb.freshMigrated();
        store = new MarketStore(db);
        SymbolCatalog cat = SymbolCatalog.load(Optional.empty());
        symbols = List.of(cat.get("EURUSD"), cat.get("XAGUSD"));
        source = new FakeHistorySource(symbols, FROM, TO, 7);
    }

    @AfterAll
    static void tearDown() { db.close(); }

    private static M1Importer importer(FakeHistorySource src, List<Duration> backoffSleeps) {
        // Real pacing is tested in PacerTest; here time is simulated so the IT is fast.
        Instant[] now = {Instant.EPOCH};
        Pacer pacer = new Pacer(4.0, () -> now[0], d -> now[0] = now[0].plus(d));
        return new M1Importer(src, store, pacer, new M1Importer.Settings(4.0, 4, Duration.ofSeconds(60)), backoffSleeps::add);
    }

    @Test
    void killMidRunThenRestartContinuesWithoutDuplicatesAndAllDerivedCandlesExist(@TempDir Path reports) throws Exception {
        long expectedEur = source.allM1("EURUSD").size(), expectedXag = source.allM1("XAGUSD").size();
        assertThat(expectedEur).isGreaterThan(40_000);

        // Run 1: rate-limited on calls 3 and 4 (backoff then success), then "killed" after the 9th request.
        List<Duration> sleeps = new ArrayList<>();
        M1Importer first = importer(source, sleeps);
        source.rateLimitOnCall = n -> n == 3 || n == 4;
        source.onCall = () -> { if (source.calls == 9) first.requestStop(); };
        M1Importer.Result r1 = first.run(symbols, FROM, TO);
        assertThat(sleeps).as("two rate-limit backoffs").hasSize(2);
        assertThat(sleeps.get(1)).isGreaterThanOrEqualTo(sleeps.get(0).dividedBy(2)); // exponential with jitter
        long afterKill = store.countM1("synthetic", "EURUSD");
        assertThat(afterKill).isBetween(1L, expectedEur - 1);
        assertThat(store.chunks("synthetic", "EURUSD")).extracting(MarketStore.Chunk::status).contains("PENDING");
        assertThat(r1.chunksDone()).isLessThan(6);

        // Run 2: restart. Unfinished chunks are fetched again; ON CONFLICT DO NOTHING drops the duplicates.
        source.rateLimitOnCall = n -> false;
        source.onCall = () -> {};
        M1Importer.Result r2 = importer(source, new ArrayList<>()).run(symbols, FROM, TO);
        assertThat(r2.chunksFailed()).isZero();

        // Row-count test: exactly one row per bar, no duplicates, nothing lost.
        assertThat(store.countM1("synthetic", "EURUSD")).isEqualTo(expectedEur);
        assertThat(store.countM1("synthetic", "XAGUSD")).isEqualTo(expectedXag);
        assertThat(store.scalarLong("SELECT count(*) - count(DISTINCT (feed, symbol, open_time)) FROM candle_m1")).isZero();
        assertThat(store.chunks("synthetic", "EURUSD")).extracting(MarketStore.Chunk::status).containsOnly("DONE");
        assertThat(store.chunks("synthetic", "EURUSD")).hasSize(3); // Feb, Mar, Apr
        // The chunk rows column adds up to the table contents (re-fetched duplicates count as 0 new rows).
        assertThat(store.chunks("synthetic", "EURUSD").stream().mapToLong(MarketStore.Chunk::rows).sum()).isEqualTo(expectedEur);

        // Run 3: everything DONE -> no requests at all, no new rows.
        FakeHistorySource quiet = source;
        int callsBefore = quiet.calls;
        M1Importer.Result r3 = importer(quiet, new ArrayList<>()).run(symbols, FROM, TO);
        assertThat(quiet.calls).isEqualTo(callsBefore);
        assertThat(r3.rowsInserted()).isZero();

        // Requests are month-chunked and never cross a month boundary.
        for (Instant[] range : source.requestedRanges) {
            assertThat(range[0].atZone(java.time.ZoneOffset.UTC).getMonth()).isEqualTo(range[1].minusNanos(1).atZone(java.time.ZoneOffset.UTC).getMonth());
            assertThat(Duration.between(range[0], range[1])).isLessThanOrEqualTo(source.span);
        }

        // Derived candles exist and equal a direct rebuild from the source bars.
        List<Candle> h4 = store.loadDerived("synthetic", "EURUSD", Timeframe.H4, Instant.parse("2024-03-11T00:00:00Z"), Instant.parse("2024-03-12T00:00:00Z"));
        assertThat(h4).extracting(c -> c.openTime().toString())
                .containsExactly("2024-03-11T01:00:00Z", "2024-03-11T05:00:00Z", "2024-03-11T09:00:00Z", "2024-03-11T13:00:00Z",
                        "2024-03-11T17:00:00Z", "2024-03-11T21:00:00Z"); // NY on EDT after 10 March

        // Gap report: weekends expected, synthetic quiet stretches listed or counted; written as markdown.
        Instant runAt = Instant.parse("2024-05-01T00:00:00Z");
        List<GapReport.SymbolResult> gaps = new ArrayList<>();
        for (SymbolDef d : symbols) gaps.add(GapReport.run(store, "synthetic", d, runAt, 5));
        assertThat(gaps.getFirst().expectedSummary()).containsKey("WEEKEND");
        assertThat(gaps.getFirst().expectedSummary().get("WEEKEND")[0]).isEqualTo(9); // 10 weeks of data -> 9 weekends inside
        assertThat(gaps.get(1).expectedSummary()).containsKey("DAILY_BREAK"); // XAG metal break
        Path gapFile = GapReport.write(reports, "synthetic", runAt, gaps, 5);
        assertThat(Files.readString(gapFile)).contains("| EURUSD |").contains("WEEKEND");

        // Spot-check: 20 per timeframe per symbol, reproducible with the same seed.
        List<Candle> pick1 = SpotCheck.pick(store, "synthetic", source.priceBasis(), symbols.getFirst(), FROM, TO, 20, 1);
        List<Candle> pick2 = SpotCheck.pick(store, "synthetic", source.priceBasis(), symbols.getFirst(), FROM, TO, 20, 1);
        assertThat(pick1).hasSize(60).isEqualTo(pick2);
        Path spot = SpotCheck.write(reports, "synthetic", runAt, Map.of("EURUSD", pick1), Map.of("EURUSD", 5));
        assertThat(Files.readString(spot)).contains("Open server time").contains("| EURUSD | H4 |");
    }

    @Test
    void chunkFailsAfterMaxAttemptsAndIsRetriedOnTheNextRun() throws Exception {
        SymbolDef eur = symbols.getFirst();
        Instant from = Instant.parse("2024-02-12T00:00:00Z"), to = Instant.parse("2024-02-19T00:00:00Z");
        FakeHistorySource src = new FakeHistorySource("synthetic2", List.of(eur), from, to, 11);
        src.rateLimitOnCall = n -> true; // the feed keeps refusing
        List<Duration> sleeps = new ArrayList<>();
        M1Importer.Result r = importer(src, sleeps).run(List.of(eur), from, to);
        assertThat(r.chunksFailed()).isEqualTo(1);
        assertThat(sleeps).hasSize(3); // 4 attempts, 3 waits
        assertThat(store.chunks("synthetic2", "EURUSD")).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo("FAILED");
            assertThat(c.attempts()).isEqualTo(4);
        });

        src.rateLimitOnCall = n -> false;
        assertThat(importer(src, new ArrayList<>()).run(List.of(eur), from, to).chunksDone()).isEqualTo(1);
        assertThat(store.chunks("synthetic2", "EURUSD")).singleElement().extracting(MarketStore.Chunk::status).isEqualTo("DONE");
    }
}
