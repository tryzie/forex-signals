package dev.forexsignals.strategy.impl;

import dev.forexsignals.market.Candle;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.strategy.StrategyRunner;
import dev.forexsignals.strategy.api.Direction;
import dev.forexsignals.strategy.api.ExitDecision;
import dev.forexsignals.strategy.api.IndicatorKind;
import dev.forexsignals.strategy.api.LookaheadViolation;
import dev.forexsignals.strategy.api.OpenTradeView;
import dev.forexsignals.strategy.api.SignalProposal;
import dev.forexsignals.strategy.api.StopRule;
import dev.forexsignals.strategy.api.Strategy;
import dev.forexsignals.strategy.api.StrategyContext;
import dev.forexsignals.strategy.api.StrategyMeta;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pre-registered confirmation gate (docs/hyp/eurusd-confirmation.md §5) and the guarded cross-symbol context:
 * truth table, the 1-minute reference-bar rule, same-instant H1 bars, warm-up and lookahead.
 */
class ConfirmationGateTest {

    static final Instant T = Instant.parse("2024-03-06T15:00:00Z"); // signal time: an M15 close that is also an H1 close
    static final Instant REF = Instant.parse("2024-03-06T13:00:00Z"); // reference bar 13:00-14:00 (closed >= 1 min before T)

    /** Inner strategy that proposes a fixed direction at every M15 close (isolates the gate). */
    static Strategy always(Direction d) {
        StrategyMeta meta = new StrategyMeta("always_" + d, "1", "test", List.of("EURUSD"), Timeframe.M15, List.of(), Map.of(), List.of());
        return new Strategy() {
            @Override public StrategyMeta meta() { return meta; }
            @Override public Optional<SignalProposal> onCandleClose(StrategyContext ctx) {
                return Optional.of(new SignalProposal(d, StopRule.atrMultiple(0.001, 1.5), 2, 10, List.of(), "", "", Map.of()));
            }
            @Override public Optional<ExitDecision> onOpenSignalCandleClose(StrategyContext c, OpenTradeView o) { return Optional.empty(); }
        };
    }

    /** n H1 bars ending at end, each moving by step (trend sign = up/down vs its EMA20 after warm-up); flat if step 0. */
    static List<Candle> h1(String sym, int n, double step, Instant end) {
        List<Candle> out = new ArrayList<>();
        double px = 1.0;
        for (int i = 0; i < n; i++) {
            Instant o = end.minus(Duration.ofHours(n - i));
            double c = px + step;
            out.add(new Candle("t", sym, Timeframe.H1, o, o.plusSeconds(3600), px, Math.max(px, c), Math.min(px, c), c, 10, Double.NaN, Double.NaN, Double.NaN, Candle.Source.DERIVED));
            px = c;
        }
        return out;
    }

    static List<Candle> eurM15(int n, Instant end) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Instant o = end.minus(Duration.ofMinutes(15L * (n - i)));
            out.add(new Candle("t", "EURUSD", Timeframe.M15, o, o.plusSeconds(900), 1.1, 1.1, 1.1, 1.1, 10, Double.NaN, Double.NaN, Double.NaN, Candle.Source.DERIVED));
        }
        return out;
    }

    /** Feeds everything in close-time order (one batch per instant); returns the proposals of the last batch. */
    static List<SignalProposal> run(Strategy s, List<Candle> all) {
        TreeMap<Instant, List<Candle>> b = new TreeMap<>();
        for (Candle c : all) b.computeIfAbsent(c.closeTime(), k -> new ArrayList<>()).add(c);
        StrategyRunner r = new StrategyRunner(List.of(s), 1);
        StrategyRunner.Result last = null;
        for (var e : b.entrySet()) last = r.onCandlesClosed(e.getKey(), e.getValue(), (id, sym) -> Map.of());
        return last.proposals().stream().map(StrategyRunner.Proposal::proposal).toList();
    }

    static List<Candle> world(double usdx, double gbp, double aud, double xau, int bars, Instant end) {
        List<Candle> all = new ArrayList<>(eurM15(20, T));
        all.addAll(h1("USDX", bars, usdx, end));
        all.addAll(h1("GBPUSD", bars, gbp, end));
        all.addAll(h1("AUDUSD", bars, aud, end));
        all.addAll(h1("XAUUSD", bars, xau, end));
        return all;
    }

    @Test
    void referenceBarIsTheLatestH1ClosedAtLeastOneMinuteBeforeTheSignal() {
        assertThat(Confirmed.referenceOpen(Instant.parse("2024-03-06T15:00:00Z"))).isEqualTo(Instant.parse("2024-03-06T13:00:00Z"));
        assertThat(Confirmed.referenceOpen(Instant.parse("2024-03-06T15:15:00Z"))).isEqualTo(Instant.parse("2024-03-06T14:00:00Z"));
        assertThat(Confirmed.referenceOpen(Instant.parse("2024-03-06T15:45:00Z"))).isEqualTo(Instant.parse("2024-03-06T14:00:00Z"));
    }

    @Test
    void truthTable() {
        // Weak dollar, GBP and AUD up, gold down: long allowed (2 of 3), short not.
        assertThat(run(new Confirmed("c", always(Direction.LONG)), world(-0.001, 0.001, 0.001, -0.001, 90, T))).hasSize(1);
        assertThat(run(new Confirmed("c", always(Direction.SHORT)), world(-0.001, 0.001, 0.001, -0.001, 90, T))).isEmpty();
        // Weak dollar but only one peer up: blocked.
        assertThat(run(new Confirmed("c", always(Direction.LONG)), world(-0.001, 0.001, -0.001, -0.001, 90, T))).isEmpty();
        // Strong dollar, all peers down: short allowed, long blocked.
        assertThat(run(new Confirmed("c", always(Direction.SHORT)), world(0.001, -0.001, -0.001, -0.001, 90, T))).hasSize(1);
        assertThat(run(new Confirmed("c", always(Direction.LONG)), world(0.001, -0.001, -0.001, -0.001, 90, T))).isEmpty();
        // Peers fine but the dollar is not weak: blocked.
        assertThat(run(new Confirmed("c", always(Direction.LONG)), world(0.001, 0.001, 0.001, 0.001, 90, T))).isEmpty();
    }

    @Test
    void reasonsRecordEveryConditionChecked() {
        var p = run(new Confirmed("c", always(Direction.LONG)), world(-0.001, 0.001, 0.001, -0.001, 90, T)).getFirst();
        assertThat(p.reasons()).extracting(r -> r.rule()).anyMatch(r -> r.startsWith("USDX below EMA20(H1) on the bar opening " + REF))
                .contains("GBPUSD above EMA20(H1)", "AUDUSD above EMA20(H1)", "XAUUSD above EMA20(H1)", "peers agreeing (>= 2 of 3)");
    }

    @Test
    void missingUsdxBarOrATieWithTheEmaDoesNotAgree() {
        // USDX's last bar is 12:00-13:00: no bar opening at the 13:00 reference -> gate fails.
        List<Candle> noUsdx = new ArrayList<>(eurM15(20, T));
        noUsdx.addAll(h1("USDX", 90, -0.001, T.minus(Duration.ofHours(2))));
        for (String p : List.of("GBPUSD", "AUDUSD", "XAUUSD")) noUsdx.addAll(h1(p, 90, 0.001, T));
        assertThat(run(new Confirmed("c", always(Direction.LONG)), noUsdx)).isEmpty();
        // Flat USDX: close equals its EMA -> neither up nor down -> fails.
        assertThat(run(new Confirmed("c", always(Direction.LONG)), world(0.0, 0.001, 0.001, 0.001, 90, T))).isEmpty();
    }

    @Test
    void contextWarmupHidesTheEmaUntil70Bars() {
        assertThat(run(new Confirmed("c", always(Direction.LONG)), world(-0.001, 0.001, 0.001, 0.001, 60, T))).as("60 H1 bars < 70").isEmpty();
        assertThat(run(new Confirmed("c", always(Direction.LONG)), world(-0.001, 0.001, 0.001, 0.001, 75, T))).hasSize(1);
    }

    @Test
    void sameInstantH1BarsOfOtherSymbolsNeverChangeTheDecision() {
        // The peers' H1 bar 14:00-15:00 closes exactly at T and flips them DOWN. Whether those bars are applied before
        // EURUSD is evaluated (same batch) or after it (a later batch, as happens when their ticks arrive later), the gate
        // must read the 13:00 bar and decide the same.
        List<Candle> base = world(-0.001, 0.001, 0.001, 0.001, 90, T.minus(Duration.ofHours(1)));
        List<Candle> flips = new ArrayList<>();
        for (String p : List.of("GBPUSD", "AUDUSD", "XAUUSD")) {
            Instant o = T.minus(Duration.ofHours(1));
            flips.add(new Candle("t", p, Timeframe.H1, o, T, 1.09, 1.09, 0.5, 0.5, 10, Double.NaN, Double.NaN, Double.NaN, Candle.Source.DERIVED));
        }
        List<Candle> together = new ArrayList<>(base);
        together.addAll(flips);
        assertThat(run(new Confirmed("c", always(Direction.LONG)), together)).as("flipping bars present at evaluation").hasSize(1);
        assertThat(run(new Confirmed("c", always(Direction.LONG)), base)).as("flipping bars not yet built").hasSize(1);
    }

    /** A strategy that reads context in arbitrary ways. */
    static Strategy reader(Consumer<StrategyContext> peek) {
        Confirmed decl = new Confirmed("decl", always(Direction.LONG)); // borrow the declared context
        StrategyMeta m = decl.meta();
        return new Strategy() {
            @Override public StrategyMeta meta() { return m; }
            @Override public Optional<SignalProposal> onCandleClose(StrategyContext ctx) { peek.accept(ctx); return Optional.empty(); }
            @Override public Optional<ExitDecision> onOpenSignalCandleClose(StrategyContext c, OpenTradeView o) { return Optional.empty(); }
        };
    }

    @Test
    void crossSymbolLookaheadIsCaught() {
        List<Candle> w = world(-0.001, 0.001, 0.001, 0.001, 90, T);
        // The 15:00-16:00 USDX bar cannot be closed at 15:00: asking for it is a lookahead attempt, even though it does not exist.
        assertThatThrownBy(() -> run(reader(ctx -> ctx.contextBar("USDX", Timeframe.H1, T)), w)).isInstanceOf(LookaheadViolation.class);
        assertThatThrownBy(() -> run(reader(ctx -> ctx.contextInd("GBPUSD", Timeframe.H1, IndicatorKind.EMA, 20, T)), w)).isInstanceOf(LookaheadViolation.class);
        // Undeclared symbol or indicator.
        assertThatThrownBy(() -> run(reader(ctx -> ctx.contextBar("USDJPY", Timeframe.H1, REF)), w)).isInstanceOf(LookaheadViolation.class);
        assertThatThrownBy(() -> run(reader(ctx -> ctx.contextInd("USDX", Timeframe.H1, IndicatorKind.EMA, 50, REF)), w)).isInstanceOf(LookaheadViolation.class);
        // The 14:00-15:00 bar closes exactly at now: closed, readable.
        run(reader(ctx -> { if (ctx.now().equals(T)) assertThat(ctx.contextBar("USDX", Timeframe.H1, T.minus(Duration.ofHours(1)))).isPresent(); }), w);
    }

    @Test
    void engineFeedingAnUnclosedContextBarIsCaught() {
        StrategyRunner r = new StrategyRunner(List.of(new Confirmed("c", always(Direction.LONG))), 1);
        Candle leaked = new Candle("t", "USDX", Timeframe.H1, T, T.plusSeconds(3600), 1, 1, 1, 1, 1, Double.NaN, Double.NaN, Double.NaN, Candle.Source.DERIVED);
        assertThatThrownBy(() -> r.onCandlesClosed(T, List.of(eurM15(1, T).getFirst(), leaked), (id, s) -> Map.of()))
                .isInstanceOf(LookaheadViolation.class).hasMessageContaining("unclosed candle");
    }

    @Test
    void confirmedRandomControlOnlyKeepsDirectionsTheGateAllows() {
        var base = new EurusdEmaBase();
        var ctrl = new Confirmed("eurusd_ema_conf__random_conf", new RandomControl(base, 1.0));
        assertThat(ctrl.meta().contextSymbols()).containsExactly("USDX", "GBPUSD", "AUDUSD", "XAUUSD");
        assertThat(ctrl.meta().symbols()).containsExactly("EURUSD");
        Map<String, Object> p = new LinkedHashMap<>(ctrl.meta().params());
        assertThat(p).containsEntry("confirmationInner", "eurusd_ema_base__random@1");
    }
}
