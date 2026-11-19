package dev.forexsignals.history;

import dev.forexsignals.market.SymbolCatalog.SymbolDef;
import dev.forexsignals.market.Tick;
import dev.forexsignals.store.Database;
import dev.forexsignals.store.MarketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2 spread model input (PLAN §14.4): one cTrader tick-history week per quarter.
 *
 * Week rule (deterministic, recorded in every report): for each quarter, the week starting at 00:00 UTC on the
 * SECOND SUNDAY of the quarter's middle month (Feb, May, Aug, Nov) and lasting 6 days (to Saturday 00:00 UTC),
 * i.e. one full trading week away from quarter-end and year-end holidays.
 *
 * For each week and symbol: the merged bid/ask ticks are fetched (paced), stored in the tick table (so the fill-bias
 * replay can reuse them), and per-session-bucket median/p95 spreads are written to spread_stats as TICK_SAMPLE with
 * the quarter label. Resumable: a week whose TICK_SAMPLE statistics exist is done and skipped. A week cut short (crash,
 * connection reset) is fetched again; its ticks carry deterministic sequence numbers (their index in the week), so the
 * re-insert is de-duplicated by the primary key. Each fetch is retried with backoff (the next request reconnects).
 */
public final class SpreadSampler {

    private static final Logger log = LoggerFactory.getLogger(SpreadSampler.class);

    public record Week(String label, Instant from, Instant to) {}

    private SpreadSampler() {}

    /** The sample weeks for every quarter whose sample week ends on or before {@code until}. */
    public static List<Week> weeks(int firstYear, Instant until) {
        List<Week> out = new ArrayList<>();
        for (int y = firstYear; ; y++) {
            for (int q = 1; q <= 4; q++) {
                LocalDate middle = LocalDate.of(y, (q - 1) * 3 + 2, 1);
                LocalDate secondSunday = middle.with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.SUNDAY));
                Instant from = secondSunday.atStartOfDay(ZoneOffset.UTC).toInstant();
                Instant to = from.plus(Duration.ofDays(6));
                if (to.isAfter(until)) return out;
                out.add(new Week(y + "Q" + q, from, to));
            }
        }
    }

    static List<Tick> fetchWithRetry(HistorySource source, SymbolDef d, Instant a, Instant b) throws Exception {
        dev.forexsignals.feed.Backoff backoff = new dev.forexsignals.feed.Backoff(Duration.ofSeconds(60));
        for (int attempt = 1; ; attempt++) {
            try {
                return source.fetchTicks(d, a, b);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                if (attempt >= 8) throw e;
                Duration wait = backoff.next();
                log.warn("spread sample {} {} .. {}: attempt {} failed ({}); retrying in {} ms", d.code(), a, b, attempt, e.toString(), wait.toMillis());
                Thread.sleep(wait);
            }
        }
    }

    public static void run(HistorySource source, MarketStore store, List<SymbolDef> symbols, List<Week> weeks) throws Exception {
        for (Week w : weeks) {
            store.db().ensurePartitions(w.from(), w.to());
            for (SymbolDef d : symbols) {
                if (store.scalarLong("SELECT count(*) FROM spread_stats WHERE feed = ? AND symbol = ? AND source = 'TICK_SAMPLE' AND window_start = ?",
                        source.feed(), d.code(), Database.utc(w.from())) > 0) {
                    log.info("spread sample {} {}: done earlier, skipped", w.label(), d.code());
                    continue;
                }
                long stored = 0, seq = 0;
                // One day per fetch: cTrader resets long tick-history sessions every few minutes, and a retry then
                // repeats only that day (a whole-week window repeated several minutes of paging per reset).
                Duration span = Duration.ofDays(1).compareTo(source.maxSpan(null)) < 0 ? Duration.ofDays(1) : source.maxSpan(null);
                for (Instant a = w.from(); a.isBefore(w.to()); a = a.plus(span)) {
                    Instant b = a.plus(span).isBefore(w.to()) ? a.plus(span) : w.to();
                    List<Tick> raw = fetchWithRetry(source, d, a, b);
                    List<Tick> ticks = new ArrayList<>(raw.size());
                    for (Tick t : raw) ticks.add(new Tick(t.feed(), t.symbol(), t.ts(), t.bid(), t.ask(), t.quote(), t.recvTs(), seq++));
                    for (int i = 0; i < ticks.size(); i += 20_000) stored += store.insertTicks(ticks.subList(i, Math.min(ticks.size(), i + 20_000)));
                }
                log.info("spread sample {} {}: {} new ticks stored", w.label(), d.code(), stored);
                SpreadStats.compute(store, source.feed(), d, w.from(), w.to(), "TICK_SAMPLE", w.label());
            }
        }
    }
}
