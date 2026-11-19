package dev.forexsignals.history;

import dev.forexsignals.feed.Backoff;
import dev.forexsignals.market.Candle;
import dev.forexsignals.market.CandleBuilder;
import dev.forexsignals.market.SymbolCatalog.SymbolDef;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.store.MarketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resumable, idempotent M1 history import (PLAN §14 Phase 1).
 *
 * For each symbol:
 *  1. Split [from, to) into calendar-month chunks and register them in import_progress (idempotent).
 *  2. For every chunk that is not DONE: page through it in requests of at most source.maxSpan(M1),
 *     each preceded by {@link Pacer#acquire} (<= 4 requests/s). Bars are inserted with
 *     ON CONFLICT DO NOTHING as each page arrives.
 *  3. A rate-limit or I/O error retries the same page after an exponential backoff with jitter
 *     (1 s to backoffMax). After maxAttempts the chunk is marked FAILED and the import moves on;
 *     the next run retries it.
 *  4. When every page of a chunk is in, the chunk is marked DONE.
 * Killing the process at any point is safe: unfinished chunks are still PENDING, are fetched again on the
 * next run, and the duplicate bars are ignored by the primary key.
 *
 * After the M1 pass, higher timeframes for the imported range are rebuilt from candle_m1 with the same
 * CandleBuilder used live (onClosedM1) and upserted into the candle table.
 */
public final class M1Importer {

    private static final Logger log = LoggerFactory.getLogger(M1Importer.class);

    public record Settings(double maxReqPerSec, int maxAttempts, Duration backoffMax) {}

    public record Result(int chunksDone, int chunksFailed, long rowsInserted, long requests) {}

    private final HistorySource source;
    private final MarketStore store;
    private final Pacer pacer;
    private final Settings settings;
    private final Pacer.Sleeper backoffSleeper;
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private long requests;

    public M1Importer(HistorySource source, MarketStore store, Pacer pacer, Settings settings, Pacer.Sleeper backoffSleeper) {
        this.source = source;
        this.store = store;
        this.pacer = pacer;
        this.settings = settings;
        this.backoffSleeper = backoffSleeper;
    }

    /** Asks the import to stop after the current request (used by tests to simulate a kill, and by SIGTERM). */
    public void requestStop() { stopRequested.set(true); }

    public long requests() { return requests; }

    /** Calendar-month chunks covering [from, to), clipped to the range. */
    public static List<Instant[]> monthChunks(Instant from, Instant to) {
        List<Instant[]> out = new ArrayList<>();
        Instant cur = from;
        while (cur.isBefore(to)) {
            Instant nextMonth = cur.atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1).plusMonths(1).toInstant();
            Instant end = nextMonth.isBefore(to) ? nextMonth : to;
            out.add(new Instant[]{cur, end});
            cur = end;
        }
        return out;
    }

    public Result run(List<SymbolDef> symbols, Instant from, Instant to) throws InterruptedException {
        int done = 0, failed = 0;
        long rows = 0;
        store.db().ensurePartitions(from, to);

        for (SymbolDef sym : symbols) {
            List<MarketStore.Chunk> open = store.openChunks(source.feed(), sym.code(), monthChunks(from, to));
            log.info("import {} {}: {} chunk(s) to do in {} .. {}", source.feed(), sym.code(), open.size(), from, to);

            for (MarketStore.Chunk chunk : open) {
                if (stopRequested.get()) return new Result(done, failed, rows, requests);
                long chunkRows = 0;
                boolean chunkOk = true;
                String lastError = null;
                Instant cursor = chunk.start();
                Duration span = source.maxSpan(Timeframe.M1);

                // Page through the chunk.
                while (cursor.isBefore(chunk.end())) {
                    Instant pageEnd = cursor.plus(span).isBefore(chunk.end()) ? cursor.plus(span) : chunk.end();
                    Backoff backoff = new Backoff(settings.backoffMax());
                    List<Candle> bars = null;
                    for (int attempt = 1; attempt <= settings.maxAttempts() && bars == null; attempt++) {
                        if (stopRequested.get()) return new Result(done, failed, rows, requests);
                        pacer.acquire();
                        requests++;
                        try {
                            bars = source.fetchM1(sym, cursor, pageEnd);
                        } catch (HistorySource.RateLimitedException e) {
                            lastError = "rate limited: " + e.getMessage();
                        } catch (InterruptedException e) {
                            throw e;
                        } catch (Exception e) {
                            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                        }
                        if (bars == null) {
                            store.updateChunk(chunk, "PENDING", 0, true, lastError);
                            Duration wait = backoff.next();
                            log.warn("import {} {} page {} .. {} attempt {} failed ({}); retrying in {} ms", source.feed(), sym.code(),
                                    cursor, pageEnd, attempt, lastError, wait.toMillis());
                            if (attempt < settings.maxAttempts()) backoffSleeper.sleep(wait);
                        }
                    }
                    if (bars == null) {
                        chunkOk = false;
                        break;
                    }
                    // A source must only return this feed's and this symbol's bars; anything else is a source bug.
                    for (Candle b : bars) {
                        if (!b.feed().equals(source.feed()) || !b.symbol().equals(sym.code()) || b.timeframe() != Timeframe.M1) {
                            throw new IllegalStateException("history source " + source.feed() + " returned a foreign bar for "
                                    + sym.code() + ": " + b.feed() + "/" + b.symbol() + "/" + b.timeframe());
                        }
                    }
                    // Keep only bars inside this page (some feeds return neighbours or, past their depth, unrelated recent bars).
                    final Instant pageFrom = cursor;
                    List<Candle> inPage = bars.stream()
                            .filter(b -> !b.openTime().isBefore(pageFrom) && b.openTime().isBefore(pageEnd))
                            .toList();
                    if (inPage.size() != bars.size()) {
                        log.warn("import {} {}: {} bar(s) outside {} .. {} discarded", source.feed(), sym.code(),
                                bars.size() - inPage.size(), cursor, pageEnd);
                    }
                    int inserted = store.insertM1Page(chunk, inPage);
                    chunkRows += inserted;
                    cursor = pageEnd;
                }

                if (chunkOk) {
                    store.updateChunk(chunk, "DONE", 0, false, null); // rows were counted page by page
                    done++;
                    log.info("import {} {} chunk {} done: {} new row(s)", source.feed(), sym.code(), chunk.start(), chunkRows);
                } else {
                    store.updateChunk(chunk, "FAILED", 0, false, lastError);
                    failed++;
                    log.error("import {} {} chunk {} FAILED after {} attempts: {}", source.feed(), sym.code(), chunk.start(),
                            settings.maxAttempts(), lastError);
                }
                rows += chunkRows;
            }
            deriveHigherTimeframes(sym, from, to);
        }
        return new Result(done, failed, rows, requests);
    }

    /** Rebuilds M5..D1 for the range from candle_m1 through the live CandleBuilder path. */
    public long deriveHigherTimeframes(SymbolDef sym, Instant from, Instant to) {
        // Start at a D1 boundary so the first derived bars are complete.
        Instant start = Timeframe.D1.bucketStart(from);
        List<Candle> batch = new ArrayList<>();
        long[] written = {0};
        CandleBuilder b = new CandleBuilder(source.feed(), source.priceBasis(), Duration.ZERO, c -> {
            batch.add(c);
            if (batch.size() >= 5000) {
                written[0] += store.upsertDerived(batch);
                batch.clear();
            }
        });
        boolean[] first = {true};
        store.streamM1(source.feed(), sym.code(), start, to, m1 -> {
            // Bars that began before the first stored minute did not see all their data.
            if (first[0]) { b.markIncompleteBefore(m1.openTime()); first[0] = false; }
            b.onClosedM1(m1);
        });
        b.flush();
        written[0] += store.upsertDerived(batch);
        log.info("derived {} higher-timeframe candle(s) for {} {}", written[0], source.feed(), sym.code());
        return written[0];
    }
}
