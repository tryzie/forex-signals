package dev.forexsignals.history;

import dev.forexsignals.market.Candle;
import dev.forexsignals.market.CandleBuilder;
import dev.forexsignals.market.PriceBasis;
import dev.forexsignals.market.SessionCalendar;
import dev.forexsignals.market.SymbolCatalog.SymbolDef;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.store.MarketStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Spot-check export (PLAN §14 Phase 1): N random M15, H1 and H4 bars per symbol, derived from candle_m1
 * with the live CandleBuilder, written as a table with the open time in UTC, EAT and broker server time,
 * for a human to compare against the cTrader chart (or TradingView, whose feed differs slightly).
 * The selection is seeded, so the same seed always picks the same bars.
 */
public final class SpotCheck {

    public static final List<Timeframe> TIMEFRAMES = List.of(Timeframe.M15, Timeframe.H1, Timeframe.H4);

    private SpotCheck() {}

    public static List<Candle> pick(MarketStore store, String feed, PriceBasis basis, SymbolDef sym, Instant from, Instant to,
                                    int perTimeframe, long seed) {
        Map<Timeframe, List<Candle>> byTf = new EnumMap<>(Timeframe.class);
        CandleBuilder b = new CandleBuilder(feed, basis, Duration.ZERO, c -> {
            if (TIMEFRAMES.contains(c.timeframe())) byTf.computeIfAbsent(c.timeframe(), k -> new ArrayList<>()).add(c);
        });
        store.streamM1(feed, sym.code(), Timeframe.D1.bucketStart(from), to, b::onClosedM1);
        // flush() is deliberately not called: the last, possibly incomplete, bars are never offered for checking.
        List<Candle> out = new ArrayList<>();
        Random r = new Random(seed ^ sym.code().hashCode());
        for (Timeframe tf : TIMEFRAMES) {
            List<Candle> all = new ArrayList<>(byTf.getOrDefault(tf, List.of()));
            for (int i = 0; i < perTimeframe && !all.isEmpty(); i++) out.add(all.remove(r.nextInt(all.size())));
        }
        out.sort(Comparator.comparing((Candle c) -> c.timeframe()).thenComparing(Candle::openTime));
        return out;
    }

    public static Path write(Path dir, String feed, Instant runAt, Map<String, List<Candle>> bySymbol, Map<String, Integer> digits) throws IOException {
        Files.createDirectories(dir);
        StringBuilder md = new StringBuilder("# Spot-check: feed " + feed + "\n\nGenerated " + runAt
                + " UTC. Bars are derived from imported M1 with the live candle builder. Server time = New York + 7 h "
                + "(the cTrader/Pepperstone chart time, UTC+2 in northern winter, UTC+3 in summer).\n"
                + "Compare each row with the chart at the server time shown. TradingView's feed differs from Pepperstone's, "
                + "so expect small differences there.\n\n"
                + "| Symbol | TF | Open UTC | Open EAT | Open server time | Open | High | Low | Close | Ticks | Matches chart? |\n"
                + "|---|---|---|---|---|---|---|---|---|---|---|\n");
        StringBuilder csv = new StringBuilder("symbol,timeframe,open_utc,open_eat,open_server,open,high,low,close,tick_count\n");
        for (var e : bySymbol.entrySet()) {
            String fmt = "%." + digits.getOrDefault(e.getKey(), 5) + "f";
            for (Candle c : e.getValue()) {
                String o = String.format(fmt, c.open()), h = String.format(fmt, c.high()), l = String.format(fmt, c.low()), cl = String.format(fmt, c.close());
                md.append("| ").append(e.getKey()).append(" | ").append(c.timeframe()).append(" | ").append(c.openTime()).append(" | ")
                        .append(SessionCalendar.eat(c.openTime())).append(" | ").append(SessionCalendar.server(c.openTime())).append(" | ")
                        .append(o).append(" | ").append(h).append(" | ").append(l).append(" | ").append(cl).append(" | ").append(c.tickCount())
                        .append(" |  |\n");
                csv.append(String.join(",", e.getKey(), c.timeframe().name(), c.openTime().toString(), SessionCalendar.eat(c.openTime()),
                        SessionCalendar.server(c.openTime()), o, h, l, cl, String.valueOf(c.tickCount()))).append('\n');
            }
        }
        String stem = "spotcheck-" + feed + "-" + runAt.toString().replace(':', '-');
        Files.writeString(dir.resolve(stem + ".csv"), csv);
        Path out = dir.resolve(stem + ".md");
        Files.writeString(out, md);
        return out;
    }
}
