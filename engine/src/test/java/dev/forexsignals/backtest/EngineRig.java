package dev.forexsignals.backtest;

import dev.forexsignals.feed.SyntheticFeed;
import dev.forexsignals.market.Candle;
import dev.forexsignals.market.CandleBuilder;
import dev.forexsignals.market.PriceBasis;
import dev.forexsignals.market.Tick;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.paper.CostModel;
import dev.forexsignals.risk.Rig;
import dev.forexsignals.risk.RiskGate;
import dev.forexsignals.signal.Signal;
import dev.forexsignals.strategy.api.Strategy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Synthetic M1 data and engine construction for engine-level tests (no database, no network). */
public final class EngineRig {

    public static final Instant FROM = Instant.parse("2024-01-07T22:00:00Z");
    public static final Instant TO = Instant.parse("2024-03-30T00:00:00Z");

    /** M1 bars built from synthetic ticks (bid), per symbol. */
    public static Map<String, M1Series> m1(List<String> symbols, Instant from, Instant to, long seed) {
        Map<String, M1Series> out = new LinkedHashMap<>();
        for (String sym : symbols) {
            M1Series s = new M1Series("synthetic", sym);
            CandleBuilder b = new CandleBuilder("synthetic", PriceBasis.BID, Duration.ZERO, c -> { if (c.timeframe() == Timeframe.M1) s.add(c); });
            new SyntheticFeed(seed).generate(List.of(Rig.CATALOG.get(sym)), from, to).forEach(b::onTick);
            b.finishAt(to);
            out.put(sym, s);
        }
        return out;
    }

    public static Engine engine(List<Strategy> strategies, CostModel costs, Map<String, M1Series> data, long seed) {
        RiskGate gate = new RiskGate(RiskGate.Config.defaults(), costs, null);
        return new Engine(new Engine.Settings("synthetic", PriceBasis.BID, Duration.ofSeconds(2), Duration.ofSeconds(10), 10_000, seed, true, true, false),
                strategies, Rig.CATALOG, gate, costs, (sym, t) -> BacktestRunner.at(data, sym, t), sym -> true);
    }

    /** Backtest; returns the tick stream it fed (for replay). */
    public static List<Tick> backtest(Engine e, Map<String, M1Series> data, Instant from, Instant to) {
        List<Tick> tape = new ArrayList<>();
        BacktestRunner.run(e, data, Rig.spreads(Rig.MEDIANS), from, to, tape::add);
        return tape;
    }

    /** Replay: the same calls live and replay make: clock step then tick, for every tick in order. */
    public static void replay(Engine e, Iterable<Tick> ticks, Instant to) {
        for (Tick t : ticks) {
            e.onTime(t.ts());
            e.onTick(t);
        }
        e.onTime(to);
        e.finish(to);
    }

    /** Every result field that must be identical between two runs (by stable key). */
    public static List<String> fingerprint(List<Signal> signals) {
        List<String> out = new ArrayList<>();
        for (Signal s : signals) {
            out.add(String.join("|", s.key(), s.direction.name(), s.state.name(), String.valueOf(s.closeReason), String.valueOf(s.reference),
                    String.valueOf(s.initialStop), String.valueOf(s.target), String.valueOf(s.lots), String.valueOf(s.fill), String.valueOf(s.filledAt),
                    String.valueOf(s.close), String.valueOf(s.closedAt), String.valueOf(s.grossR), String.valueOf(s.netR), String.valueOf(s.worstCaseNetR),
                    String.valueOf(s.pnlUsd), s.flags.toString(), String.valueOf(s.barsSinceEntry), String.valueOf(s.reasons)));
        }
        return out;
    }

    public static Candle bar(M1Series s, int i) { return s.candle(i); }

    private EngineRig() {}
}
