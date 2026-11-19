package dev.forexsignals.market;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds candles on the feed's price basis (bid for cTrader): M1 from ticks, and M5/M15/H1/H4/D1 from closed M1 candles only (PLAN §5).
 * Live, replay and import all use this one class (CLAUDE.md rule 3).
 *
 * Rules:
 *  - A bar closes when the first tick of a later period arrives, or when the clock passes its end plus
 *    the grace time ({@link #onTime}), whichever comes first. A derived bar also closes as soon as the
 *    M1 bar covering its last minute closes.
 *  - Periods without ticks produce no candle (matches cTrader trendbars). Nothing is invented.
 *  - A tick older than the current M1 bar, or older than the end of the last closed M1 bar, is late:
 *    it is counted and reported, never applied. No repainting.
 *  - Within a bar, open/close are the earliest/latest tick by timestamp (ties: arrival order), so the
 *    result does not depend on arrival order inside a minute.
 *  - Closed candles are emitted in timeframe order (M1 first) to the {@link Listener}.
 *
 * Not thread-safe: the engine event loop is single-threaded.
 */
public final class CandleBuilder {

    public interface Listener {
        void onCandleClosed(Candle candle);

        default void onLateTick(Tick tick, Instant barOrBoundary) {}
    }

    /** Derived timeframes, in emission order. */
    public static final List<Timeframe> DERIVED = List.of(Timeframe.M5, Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1);

    private final String feed;
    private final PriceBasis basis;
    private final Duration grace;
    private final Listener listener;
    private final Map<String, SymbolState> states = new HashMap<>();

    public CandleBuilder(String feed, PriceBasis basis, Duration grace, Listener listener) {
        this.feed = feed;
        this.basis = basis;
        this.grace = grace;
        this.listener = listener;
    }

    // ----------------------------------------------------------------------------- inputs

    /** Live/replay input. Closes any bars the tick proves finished, then applies it to the forming M1 bar. */
    public void onTick(Tick t) {
        SymbolState s = state(t.symbol());

        // 1. Late tick? Older than the forming bar, or older than the end of the last closed M1 bar.
        Instant tickBar = Timeframe.M1.bucketStart(t.ts());
        if ((s.m1 != null && tickBar.isBefore(s.m1.start)) || (s.lastClosedM1End != null && t.ts().isBefore(s.lastClosedM1End))) {
            s.lateTicks++;
            listener.onLateTick(t, s.m1 != null ? s.m1.start : s.lastClosedM1End);
            return;
        }

        // 2. The tick proves every bar ending at or before its timestamp is finished.
        closeFinished(s, t.ts(), Duration.ZERO);

        // 3. Apply to the forming M1 bar (start a new one if needed).
        if (Double.isNaN(t.price(basis))) return; // no usable price for this basis (counted nowhere: feed bug)
        if (s.m1 == null) s.m1 = new M1Bar(tickBar);
        M1Bar b = s.m1;
        double px = t.price(basis);
        if (b.count == 0 || t.ts().isBefore(b.firstTs)) { b.open = px; b.firstTs = t.ts(); }
        if (b.count == 0 || !t.ts().isBefore(b.lastTs)) { b.close = px; b.lastTs = t.ts(); }
        if (b.count == 0 || px > b.high) b.high = px;
        if (b.count == 0 || px < b.low) b.low = px;
        b.count++;
        if (!Double.isNaN(t.spread())) b.addSpread(t.spread());
    }

    /** Clock input: closes bars whose end + grace has passed. Call regularly in live, per event in replay. */
    public void onTime(Instant now) {
        for (SymbolState s : states.values()) closeFinished(s, now, grace);
    }

    /**
     * Import/backtest input: a closed M1 candle (e.g. from the history importer). It goes through exactly
     * the same higher-timeframe aggregation as M1 bars built from ticks. The M1 itself is not re-emitted.
     */
    public void onClosedM1(Candle m1) {
        if (m1.timeframe() != Timeframe.M1) throw new IllegalArgumentException("onClosedM1 needs an M1 candle");
        SymbolState s = state(m1.symbol());
        if (s.lastClosedM1End != null && m1.openTime().isBefore(s.lastClosedM1End)) {
            s.lateTicks++; // out-of-order history bar: never applied
            return;
        }
        // Derived bars that end at or before this M1's start are finished.
        for (Timeframe tf : DERIVED) {
            Agg a = s.derived.get(tf);
            if (a != null && !m1.openTime().isBefore(a.end)) closeDerived(s, tf);
        }
        s.lastClosedM1End = m1.closeTime();
        aggregate(s, m1);
    }

    /** Closes and emits every forming bar (end of a replay or import run). */
    public void flush() {
        for (SymbolState s : states.values()) {
            if (s.m1 != null) closeM1(s);
            for (Timeframe tf : DERIVED) if (s.derived.get(tf) != null) closeDerived(s, tf);
        }
    }

    public long lateTicks(String symbol) {
        SymbolState s = states.get(symbol);
        return s == null ? 0 : s.lateTicks;
    }

    // ----------------------------------------------------------------------------- closing logic

    /** Closes bars with end + slack <= t: the M1 first (which feeds the derived bars), then the derived bars. */
    private void closeFinished(SymbolState s, Instant t, Duration slack) {
        if (s.m1 != null && !t.isBefore(s.m1.end.plus(slack))) closeM1(s);
        for (Timeframe tf : DERIVED) {
            Agg a = s.derived.get(tf);
            if (a != null && !t.isBefore(a.end.plus(slack))) closeDerived(s, tf);
        }
    }

    private void closeM1(SymbolState s) {
        M1Bar b = s.m1;
        s.m1 = null;
        s.lastClosedM1End = b.end;
        double[] sp = b.spreads();
        Candle c = new Candle(feed, s.symbol, Timeframe.M1, b.start, b.end, b.open, b.high, b.low, b.close, b.count,
                sp.length == 0 ? Double.NaN : sp[0], median(sp), sp.length == 0 ? Double.NaN : sp[sp.length - 1], Candle.Source.BUILT);
        listener.onCandleClosed(c);
        // Derived bars that ended before this M1 started (a gap) close first, then the M1 is aggregated.
        for (Timeframe tf : DERIVED) {
            Agg a = s.derived.get(tf);
            if (a != null && !c.openTime().isBefore(a.end)) closeDerived(s, tf);
        }
        aggregate(s, c);
    }

    /** Adds a closed M1 to every derived timeframe; closes a derived bar when its last minute is in. */
    private void aggregate(SymbolState s, Candle m1) {
        for (Timeframe tf : DERIVED) {
            Agg a = s.derived.get(tf);
            if (a == null) {
                Instant start = tf.bucketStart(m1.openTime());
                a = new Agg(start, tf.bucketEnd(start));
                s.derived.put(tf, a);
            }
            a.add(m1);
            if (!m1.closeTime().isBefore(a.end)) closeDerived(s, tf);
        }
    }

    private void closeDerived(SymbolState s, Timeframe tf) {
        Agg a = s.derived.remove(tf);
        double[] meds = a.spreadMeds();
        listener.onCandleClosed(new Candle(feed, s.symbol, tf, a.start, a.end, a.open, a.high, a.low, a.close, a.ticks,
                a.spreadMin, median(meds), a.spreadMax, Candle.Source.DERIVED));
    }

    private SymbolState state(String symbol) { return states.computeIfAbsent(symbol, SymbolState::new); }

    /** Median of a sorted array (NaN when empty). */
    static double median(double[] sorted) {
        int n = sorted.length;
        if (n == 0) return Double.NaN;
        return (n % 2 == 1) ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    // ----------------------------------------------------------------------------- state

    private static final class SymbolState {
        final String symbol;
        M1Bar m1;
        Instant lastClosedM1End;
        long lateTicks;
        final EnumMap<Timeframe, Agg> derived = new EnumMap<>(Timeframe.class);

        SymbolState(String symbol) { this.symbol = symbol; }
    }

    private static final class M1Bar {
        final Instant start, end;
        Instant firstTs, lastTs;
        double open, high, low, close;
        int count;
        double[] spreadBuf = new double[16];
        int spreadN;

        M1Bar(Instant start) {
            this.start = start;
            this.end = Timeframe.M1.bucketEnd(start);
        }

        void addSpread(double sp) {
            if (spreadN == spreadBuf.length) spreadBuf = Arrays.copyOf(spreadBuf, spreadN * 2);
            spreadBuf[spreadN++] = sp;
        }

        double[] spreads() {
            double[] out = Arrays.copyOf(spreadBuf, spreadN);
            Arrays.sort(out);
            return out;
        }
    }

    /** A forming derived bar, aggregated from closed M1 candles. */
    private static final class Agg {
        final Instant start, end;
        double open, high, low, close;
        int ticks, n;
        double spreadMin = Double.NaN, spreadMax = Double.NaN;
        double[] meds = new double[8];
        int medN;

        Agg(Instant start, Instant end) {
            this.start = start;
            this.end = end;
        }

        void add(Candle m1) {
            if (n == 0) { open = m1.open(); high = m1.high(); low = m1.low(); }
            high = Math.max(high, m1.high());
            low = Math.min(low, m1.low());
            close = m1.close();
            ticks += m1.tickCount();
            n++;
            if (!Double.isNaN(m1.spreadMin())) spreadMin = Double.isNaN(spreadMin) ? m1.spreadMin() : Math.min(spreadMin, m1.spreadMin());
            if (!Double.isNaN(m1.spreadMax())) spreadMax = Double.isNaN(spreadMax) ? m1.spreadMax() : Math.max(spreadMax, m1.spreadMax());
            if (!Double.isNaN(m1.spreadMed())) {
                if (medN == meds.length) meds = Arrays.copyOf(meds, medN * 2);
                meds[medN++] = m1.spreadMed();
            }
        }

        double[] spreadMeds() {
            double[] out = Arrays.copyOf(meds, medN);
            Arrays.sort(out);
            return out;
        }
    }
}
