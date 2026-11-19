package dev.forexsignals.strategy;

import dev.forexsignals.indicator.Adx;
import dev.forexsignals.indicator.Atr;
import dev.forexsignals.indicator.Donchian;
import dev.forexsignals.indicator.Ema;
import dev.forexsignals.indicator.Indicator;
import dev.forexsignals.indicator.Rsi;
import dev.forexsignals.market.Candle;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.strategy.api.ExitDecision;
import dev.forexsignals.strategy.api.IndicatorKind;
import dev.forexsignals.strategy.api.IndicatorRequest;
import dev.forexsignals.strategy.api.LookaheadViolation;
import dev.forexsignals.strategy.api.OpenTradeView;
import dev.forexsignals.strategy.api.SignalProposal;
import dev.forexsignals.strategy.api.Strategy;
import dev.forexsignals.strategy.api.StrategyContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * Runs strategies on closed candles (one code path for backtest, replay and live).
 *
 * The engine hands over every candle that closed at one instant ({@link #onCandlesClosed}). The runner first applies
 * ALL of them to the bar series and indicators (so an H1 bar closing together with an M15 bar counts as closed), then
 * evaluates each strategy whose entry timeframe closed for one of its symbols.
 *
 * Guards (Phase 2 criterion C):
 *  - Engine side: a candle whose close time is after the evaluation instant is rejected with LookaheadViolation.
 *  - Context side: ago < 0, a bar or indicator value stamped after now, or an undeclared timeframe/indicator throws
 *    LookaheadViolation. Violations are never caught here; the run fails.
 *  - Warm-up: a strategy is not called for a symbol until every timeframe it declares has
 *    max(3 x longest declared period + 10, the indicators' own minimum) closed bars. Skipped calls are counted.
 */
public final class StrategyRunner {

    private static final int SERIES_CAPACITY = 1024;
    private static final int VALUE_HISTORY = 64;

    /** Supplies the active trades of a strategy and symbol (key -> view) for exit management. */
    public interface OpenTrades {
        Map<Object, OpenTradeView> active(String strategyId, String symbol);
    }

    public record Proposal(Strategy strategy, String symbol, Instant at, SignalProposal proposal) {}

    public record Management(Strategy strategy, String symbol, Object tradeKey, ExitDecision decision) {}

    public record Result(List<Proposal> proposals, List<Management> management) {}

    private final List<Strategy> strategies;
    private final long seed;
    private final Map<String, SymbolData> data = new HashMap<>();
    private final Map<String, Integer> warmupNeeded = new HashMap<>();   // strategyId|tf -> bars
    private final Map<String, Long> warmupBlocked = new LinkedHashMap<>(); // strategyId -> skipped calls
    private final Map<String, Long> evaluated = new LinkedHashMap<>();     // strategyId -> calls after warm-up
    private final Map<String, RandomGenerator> randoms = new HashMap<>();

    public StrategyRunner(List<Strategy> strategies, long seed) {
        this.strategies = List.copyOf(strategies);
        this.seed = seed;
        for (Strategy s : strategies) {
            Map<Timeframe, int[]> perTf = new EnumMap<>(Timeframe.class); // {max period, max minBars}
            perTf.put(s.meta().entryTf(), new int[]{0, 1});
            s.meta().contextTfs().forEach(tf -> perTf.putIfAbsent(tf, new int[]{0, 1}));
            for (IndicatorRequest r : s.meta().indicators()) {
                if (!perTf.containsKey(r.tf())) throw new IllegalArgumentException(s.meta().id() + " declares " + r + " on an undeclared timeframe");
                int[] v = perTf.get(r.tf());
                v[0] = Math.max(v[0], r.period());
                v[1] = Math.max(v[1], newIndicator(r).minBars());
            }
            perTf.forEach((tf, v) -> warmupNeeded.put(s.meta().id() + "|" + tf, Math.max(3 * v[0] + 10, v[1])));
            warmupBlocked.put(s.meta().id(), 0L);
            evaluated.put(s.meta().id(), 0L);
        }
    }

    public long warmupBlocked(String strategyId) { return warmupBlocked.getOrDefault(strategyId, 0L); }

    /** Calls after warm-up: the "eligible bars" a random control's firing rate is measured against. */
    public long evaluated(String strategyId) { return evaluated.getOrDefault(strategyId, 0L); }

    public int warmupBars(String strategyId, Timeframe tf) { return warmupNeeded.getOrDefault(strategyId + "|" + tf, 0); }

    // ------------------------------------------------------------------------------------ input

    public Result onCandlesClosed(Instant at, List<Candle> batch, OpenTrades open) {
        // 1. Apply every candle of this instant. Engine-side guard: nothing may close after the evaluation instant.
        Set<String> entryCloses = new HashSet<>();
        for (Candle c : batch) {
            if (c.closeTime().isAfter(at)) {
                throw new LookaheadViolation("engine fed an unclosed candle: " + c.symbol() + " " + c.timeframe() + " closing "
                        + c.closeTime() + " at evaluation time " + at);
            }
            SymbolData sd = data.computeIfAbsent(c.symbol(), k -> new SymbolData());
            sd.apply(c, strategies);
            entryCloses.add(c.symbol() + "|" + c.timeframe());
        }
        // 2. Evaluate strategies whose entry timeframe closed now.
        List<Proposal> proposals = new ArrayList<>();
        List<Management> management = new ArrayList<>();
        for (Strategy s : strategies) {
            for (String symbol : s.meta().symbols()) {
                if (!entryCloses.contains(symbol + "|" + s.meta().entryTf())) continue;
                SymbolData sd = data.get(symbol);
                if (!warm(s, sd)) {
                    warmupBlocked.merge(s.meta().id(), 1L, Long::sum);
                    continue;
                }
                evaluated.merge(s.meta().id(), 1L, Long::sum);
                Ctx ctx = new Ctx(s, symbol, at, sd);
                // Exit management first (open trades), then new proposals.
                if (open != null) {
                    for (var e : open.active(s.meta().id(), symbol).entrySet()) {
                        s.onOpenSignalCandleClose(ctx, e.getValue()).ifPresent(d -> management.add(new Management(s, symbol, e.getKey(), d)));
                    }
                }
                s.onCandleClose(ctx).ifPresent(p -> proposals.add(new Proposal(s, symbol, at, p)));
            }
        }
        return new Result(proposals, management);
    }

    private boolean warm(Strategy s, SymbolData sd) {
        if (sd == null) return false;
        if (sd.count(s.meta().entryTf()) < warmupBars(s.meta().id(), s.meta().entryTf())) return false;
        for (Timeframe tf : s.meta().contextTfs()) if (sd.count(tf) < warmupBars(s.meta().id(), tf)) return false;
        return true;
    }

    static Indicator newIndicator(IndicatorRequest r) {
        return switch (r.kind()) {
            case EMA -> new Ema(r.period());
            case ATR -> new Atr(r.period());
            case RSI -> new Rsi(r.period());
            case ADX -> new Adx(r.period());
            case DONCHIAN -> new Donchian(r.period());
        };
    }

    // ------------------------------------------------------------------------------------ per-symbol data

    /** Closed bars and indicator values of one symbol, per timeframe. */
    private static final class SymbolData {
        final Map<Timeframe, Candle[]> series = new EnumMap<>(Timeframe.class);
        final Map<Timeframe, Integer> counts = new EnumMap<>(Timeframe.class);
        final Map<IndicatorRequest, Indicator> indicators = new HashMap<>();
        final Map<IndicatorRequest, double[]> values = new HashMap<>();      // ring of the last values
        final Map<IndicatorRequest, Instant[]> stamps = new HashMap<>();     // close time of the bar each value is for

        void apply(Candle c, List<Strategy> strategies) {
            Timeframe tf = c.timeframe();
            Candle[] ring = series.computeIfAbsent(tf, k -> new Candle[SERIES_CAPACITY]);
            int n = counts.getOrDefault(tf, 0);
            ring[n % SERIES_CAPACITY] = c;
            counts.put(tf, n + 1);
            // Create any indicator a strategy declares for this timeframe, then update all of this timeframe.
            for (Strategy s : strategies) {
                if (!s.meta().symbols().contains(c.symbol())) continue;
                for (IndicatorRequest r : s.meta().indicators()) if (r.tf() == tf) indicators.computeIfAbsent(r, StrategyRunner::newIndicator);
            }
            for (var e : indicators.entrySet()) {
                if (e.getKey().tf() != tf) continue;
                e.getValue().update(c);
                double[] v = values.computeIfAbsent(e.getKey(), k -> new double[VALUE_HISTORY]);
                Instant[] st = stamps.computeIfAbsent(e.getKey(), k -> new Instant[VALUE_HISTORY]);
                v[n % VALUE_HISTORY] = e.getValue().value();
                st[n % VALUE_HISTORY] = c.closeTime();
            }
        }

        int count(Timeframe tf) { return counts.getOrDefault(tf, 0); }
    }

    // ------------------------------------------------------------------------------------ the guarded context

    private final class Ctx implements StrategyContext {
        private final Strategy s;
        private final String symbol;
        private final Instant now;
        private final SymbolData sd;

        Ctx(Strategy s, String symbol, Instant now, SymbolData sd) {
            this.s = s;
            this.symbol = symbol;
            this.now = now;
            this.sd = sd;
        }

        private void declared(Timeframe tf) {
            if (tf != s.meta().entryTf() && !s.meta().contextTfs().contains(tf)) {
                throw new LookaheadViolation(s.meta().id() + " read undeclared timeframe " + tf);
            }
        }

        @Override public String symbol() { return symbol; }

        @Override public Instant now() { return now; }

        @Override
        public Candle bar(Timeframe tf, int ago) {
            declared(tf);
            if (ago < 0) throw new LookaheadViolation(s.meta().id() + " asked for a future bar (ago=" + ago + ") of " + symbol + " " + tf);
            int n = sd.count(tf);
            if (ago >= n || ago >= SERIES_CAPACITY) throw new IllegalArgumentException("only " + Math.min(n, SERIES_CAPACITY) + " bars of " + tf + " kept");
            Candle c = sd.series.get(tf)[(n - 1 - ago) % SERIES_CAPACITY];
            if (c.closeTime().isAfter(now)) {
                throw new LookaheadViolation(s.meta().id() + " saw " + symbol + " " + tf + " bar closing " + c.closeTime() + " at " + now);
            }
            return c;
        }

        @Override public int bars(Timeframe tf) { declared(tf); return sd.count(tf); }

        @Override
        public OptionalDouble ind(Timeframe tf, IndicatorKind kind, int period, int ago) {
            declared(tf);
            IndicatorRequest r = new IndicatorRequest(tf, kind, period);
            if (!s.meta().indicators().contains(r)) throw new LookaheadViolation(s.meta().id() + " read undeclared indicator " + r);
            if (ago < 0) throw new LookaheadViolation(s.meta().id() + " asked for a future indicator value (ago=" + ago + ")");
            if (ago >= VALUE_HISTORY) throw new IllegalArgumentException("only " + VALUE_HISTORY + " indicator values kept");
            int n = sd.count(tf);
            if (ago >= n) return OptionalDouble.empty();
            int idx = (n - 1 - ago) % VALUE_HISTORY;
            Instant stamp = sd.stamps.get(r)[idx];
            if (stamp.isAfter(now)) throw new LookaheadViolation(s.meta().id() + " saw " + r + " value from " + stamp + " at " + now);
            // Warm-up: no value before the timeframe has its warm-up bars (counted at that historical point).
            if (n - ago < warmupBars(s.meta().id(), tf)) return OptionalDouble.empty();
            double v = sd.values.get(r)[idx];
            return Double.isNaN(v) ? OptionalDouble.empty() : OptionalDouble.of(v);
        }

        private Donchian donchian(Timeframe tf, int n) {
            declared(tf);
            IndicatorRequest r = new IndicatorRequest(tf, IndicatorKind.DONCHIAN, n);
            if (!s.meta().indicators().contains(r)) throw new LookaheadViolation(s.meta().id() + " read undeclared indicator " + r);
            Candle latest = bar(tf, 0); // also applies the closed-bar check to the channel's newest bar
            if (latest.closeTime().isAfter(now)) throw new LookaheadViolation("future Donchian bar");
            return (Donchian) sd.indicators.get(r);
        }

        @Override
        public OptionalDouble donchianHigh(Timeframe tf, int n, boolean excludeLatest) {
            double v = donchian(tf, n).highest(excludeLatest);
            return Double.isNaN(v) ? OptionalDouble.empty() : OptionalDouble.of(v);
        }

        @Override
        public OptionalDouble donchianLow(Timeframe tf, int n, boolean excludeLatest) {
            double v = donchian(tf, n).lowest(excludeLatest);
            return Double.isNaN(v) ? OptionalDouble.empty() : OptionalDouble.of(v);
        }

        @Override
        public RandomGenerator random() {
            return randoms.computeIfAbsent(s.meta().id() + "|" + symbol,
                    k -> new SplittableRandom(seed ^ (31L * s.meta().id().hashCode() + symbol.hashCode())));
        }
    }
}
