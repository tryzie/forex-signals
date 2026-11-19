package dev.forexsignals.history;

import dev.forexsignals.market.SymbolCatalog.SymbolDef;
import dev.forexsignals.market.Tick;
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
 * the quarter label. A week whose ticks are already stored is not fetched again (resumable).
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

    public static void run(HistorySource source, MarketStore store, List<SymbolDef> symbols, List<Week> weeks) throws Exception {
        for (Week w : weeks) {
            store.db().ensurePartitions(w.from(), w.to());
            for (SymbolDef d : symbols) {
                long have = store.countTicks(source.feed(), d.code(), w.from(), w.to());
                if (have == 0) {
                    long stored = 0;
                    Duration span = source.maxSpan(null);
                    for (Instant a = w.from(); a.isBefore(w.to()); a = a.plus(span)) {
                        Instant b = a.plus(span).isBefore(w.to()) ? a.plus(span) : w.to();
                        List<Tick> ticks = source.fetchTicks(d, a, b);
                        for (int i = 0; i < ticks.size(); i += 20_000) stored += store.insertTicks(ticks.subList(i, Math.min(ticks.size(), i + 20_000)));
                    }
                    log.info("spread sample {} {}: {} ticks stored", w.label(), d.code(), stored);
                } else {
                    log.info("spread sample {} {}: {} ticks already stored, not fetched again", w.label(), d.code(), have);
                }
                SpreadStats.compute(store, source.feed(), d, w.from(), w.to(), "TICK_SAMPLE", w.label());
            }
        }
    }
}
