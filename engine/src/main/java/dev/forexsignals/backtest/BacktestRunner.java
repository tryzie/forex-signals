package dev.forexsignals.backtest;

import dev.forexsignals.market.Candle;
import dev.forexsignals.market.Tick;
import dev.forexsignals.paper.SpreadModel;
import dev.forexsignals.signal.Signal;
import dev.forexsignals.strategy.api.Direction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * M1-bar backtest (ADR 0005, PLAN §14.4): each stored M1 bid bar becomes four synthetic ticks fed to the SAME
 * {@link Engine} as replay and live: open, then the adverse extreme, then the favourable extreme, then the close.
 * "Adverse" is judged against the positions open on that symbol (lows first when all are long or there are none and
 * the bar closed up... see {@link #order}); with mixed directions the bar's own direction decides and stop-vs-target
 * ambiguity is flagged by the tracker. The ask is bid + the modelled spread (unstressed: the engine applies stress).
 *
 * Tick times are bar open + 0, 1, 2, 3 ms, so the first tick at or after alert + 10 s is the NEXT bar's open: the
 * approved M1 fill model. Every emitted tick can be tapped (tickTap) so the identical stream can be replayed.
 */
public final class BacktestRunner {

    private BacktestRunner() {}

    /** Feeds all bars in [from, to) of all series, merged by minute (symbols in name order within a minute). */
    public static void run(Engine engine, Map<String, M1Series> data, SpreadModel spreads, Instant from, Instant to, Consumer<Tick> tickTap) {
        List<M1Series> series = new ArrayList<>(new TreeMap<>(data).values());
        int[] idx = new int[series.size()];
        for (int k = 0; k < series.size(); k++) {
            M1Series s = series.get(k);
            while (idx[k] < s.n && s.open[idx[k]] < from.toEpochMilli()) idx[k]++;
        }
        long seq = 0;
        long end = to.toEpochMilli();
        while (true) {
            long next = Long.MAX_VALUE;
            for (int k = 0; k < series.size(); k++) if (idx[k] < series.get(k).n) next = Math.min(next, series.get(k).open[idx[k]]);
            if (next == Long.MAX_VALUE || next >= end) break;
            for (int k = 0; k < series.size(); k++) {
                M1Series s = series.get(k);
                if (idx[k] >= s.n || s.open[idx[k]] != next) continue;
                int i = idx[k]++;
                Instant t0 = Instant.ofEpochMilli(next);
                double spread = spreads.median(s.symbol, t0);
                double[] path = order(s.o[i], s.h[i], s.l[i], s.c[i], engine.netOpenDirection(s.symbol, t0));
                for (int j = 0; j < 4; j++) {
                    Instant ts = t0.plusMillis(j);
                    Tick tick = new Tick(s.feed, s.symbol, ts, path[j], path[j] + spread, ts, seq++);
                    engine.onTime(ts);
                    engine.onTick(tick);
                    if (tickTap != null) tickTap.accept(tick);
                }
            }
        }
        engine.onTime(to);
        engine.finish(to);
    }

    /**
     * The four bid prices of a bar: O, adverse, favourable, C. net > 0 (only longs open): low first; net < 0 (only shorts):
     * high first; otherwise (none or mixed): the usual path for the bar's direction (up bar: low first; down bar: high first).
     */
    static double[] order(double o, double h, double l, double c, int net) {
        boolean lowFirst = net > 0 || (net == 0 && c >= o);
        return lowFirst ? new double[]{o, l, h, c} : new double[]{o, h, l, c};
    }

    /** M1 lookup for the tracker's worst-case fill and ambiguity flag. */
    public static Function<String, M1Series> lookup(Map<String, M1Series> data) { return data::get; }

    public static Candle at(Map<String, M1Series> data, String symbol, Instant t) {
        M1Series s = data.get(symbol);
        return s == null ? null : s.at(t);
    }

    static int sign(Signal s) { return s.direction == Direction.LONG ? 1 : -1; }
}
