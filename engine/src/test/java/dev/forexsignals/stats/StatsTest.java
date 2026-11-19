package dev.forexsignals.stats;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Metrics, bootstrap, walk-forward and the validation gate (Phase 2 criteria G25-G28). */
class StatsTest {

    static final Instant START = Instant.parse("2023-11-20T00:00:00Z");

    /** n trades, one every `everyHours`, round-robin over symbols, net R from the supplier. */
    static List<TradeR> trades(int n, int everyHours, String[] symbols, java.util.function.IntToDoubleFunction r) {
        List<TradeR> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Instant t = START.plus(Duration.ofHours((long) i * everyHours));
            double x = r.applyAsDouble(i);
            out.add(new TradeR(symbols[i % symbols.length], t, t.plusSeconds(3600), x + 0.1, x, x - 0.05, "X"));
        }
        return out;
    }

    @Test
    void metricsByHand() {
        // +2, -1, +2, -1, -1: mean 0.2; PF 4/3; equity 2,1,3,2,1 -> max drawdown 2; win rate 40%.
        double[] r = {2, -1, 2, -1, -1};
        Metrics m = Metrics.of(trades(5, 24, new String[]{"EURUSD"}, i -> r[i]));
        assertThat(m.expectancy()).isCloseTo(0.2, within(1e-12));
        assertThat(m.profitFactor()).isCloseTo(4.0 / 3, within(1e-12));
        assertThat(m.maxDrawdownR()).isCloseTo(2.0, within(1e-12));
        assertThat(m.winRate()).isCloseTo(0.4, within(1e-12));
        assertThat(m.grossExpectancy() - m.expectancy()).as("avg cost").isCloseTo(0.1, within(1e-12));
    }

    @Test
    void randomStrategyCiIncludesZero() {
        Random rnd = new Random(1);
        List<TradeR> coin = trades(600, 30, new String[]{"EURUSD", "GBPUSD", "USDJPY"}, i -> rnd.nextBoolean() ? 1.0 : -1.0);
        Bootstrap.Ci ci = Bootstrap.meanCi(coin, 10_000, 42);
        assertThat(ci.lo()).isLessThan(0);
        assertThat(ci.hi()).isGreaterThan(0);
        assertThat(Bootstrap.meanCi(coin, 10_000, 42)).as("seeded: reproducible").isEqualTo(ci);
    }

    @Test
    void walkForwardWindowsOverThreeYears() {
        List<TradeR> t = trades(1000, 26, new String[]{"EURUSD"}, i -> 0.2);
        WalkForward.Result w = WalkForward.run(t, START, START.atZone(java.time.ZoneOffset.UTC).plusMonths(36).toInstant());
        assertThat(w.windows()).hasSize(8); // 12m IS + 3m OOS, step 3m, inside 36 months
        assertThat(w.windows().get(1).isFrom()).isEqualTo(Instant.parse("2024-02-20T00:00:00Z"));
        assertThat(w.oosTrades()).isGreaterThan(50);
        assertThat(w.verdict()).isEqualTo("PASS");
        assertThat(WalkForward.run(t.subList(0, 300), START, START.atZone(java.time.ZoneOffset.UTC).plusMonths(36).toInstant()).verdict()).as("< 50 OOS trades").isEqualTo("INCONCLUSIVE");
    }

    static ValidationGate.Inputs inputs(List<TradeR> t, List<TradeR> control, boolean oos) {
        Metrics m = Metrics.of(t);
        return new ValidationGate.Inputs(m, Bootstrap.meanCi(t, 10_000, 1), true, "trending 4 months, ranging 4 months",
                WalkForward.run(t, START, START.atZone(java.time.ZoneOffset.UTC).plusMonths(36).toInstant()), oos, oos ? "" : "parameters not frozen",
                Metrics.of(t.stream().map(x -> new TradeR(x.symbol(), x.entry(), x.exit(), x.grossR(), x.netR() - 0.05, x.worstNetR(), x.closeReason())).toList()),
                Bootstrap.differenceCi(t, control, 10_000, 1));
    }

    @Test
    void deliberatelyBadStrategyIsBlocked() {
        Random rnd = new Random(2);
        List<TradeR> bad = trades(500, 40, new String[]{"EURUSD", "GBPUSD", "USDJPY"}, i -> rnd.nextDouble() < 0.3 ? 1.5 : -1.0); // ~ -0.25R
        List<TradeR> control = trades(500, 40, new String[]{"EURUSD", "GBPUSD", "USDJPY"}, i -> rnd.nextBoolean() ? 1.0 : -1.0);
        ValidationGate.Result r = ValidationGate.evaluate(inputs(bad, control, true));
        assertThat(r.verdict()).isEqualTo(ValidationGate.Status.FAIL);
        assertThat(r.checks()).filteredOn(c -> c.number() == 2).singleElement().extracting(ValidationGate.Check::status).isEqualTo(ValidationGate.Status.FAIL);
    }

    @Test
    void goodFixturePassesAndUnfrozenParametersWithholdOos() {
        Random rnd = new Random(3);
        List<TradeR> good = trades(600, 40, new String[]{"EURUSD", "GBPUSD", "USDJPY", "AUDUSD"}, i -> rnd.nextDouble() < 0.5 ? 2.0 : -1.0); // ~ +0.5R
        List<TradeR> control = trades(600, 40, new String[]{"EURUSD", "GBPUSD", "USDJPY", "AUDUSD"}, i -> rnd.nextBoolean() ? 1.0 : -1.0);
        assertThat(ValidationGate.evaluate(inputs(good, control, true)).verdict()).isEqualTo(ValidationGate.Status.PASS);
        ValidationGate.Result withheld = ValidationGate.evaluate(inputs(good, control, false));
        assertThat(withheld.verdict()).isEqualTo(ValidationGate.Status.INCONCLUSIVE);
        assertThat(withheld.checks().get(4).observed()).startsWith("OOS WITHHELD");
    }

    @Test
    void tooFewTradesIsInconclusiveNotPass() {
        Random rnd = new Random(4);
        List<TradeR> few = trades(120, 70, new String[]{"EURUSD", "GBPUSD"}, i -> rnd.nextDouble() < 0.5 ? 2.0 : -1.0);
        var r = ValidationGate.evaluate(inputs(few, few, true));
        assertThat(r.verdict()).isNotEqualTo(ValidationGate.Status.PASS);
        assertThat(r.checks().getFirst().status()).isEqualTo(ValidationGate.Status.INCONCLUSIVE);
    }
}
