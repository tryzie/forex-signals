package dev.forexsignals.backtest;

import dev.forexsignals.stats.Bootstrap;
import dev.forexsignals.stats.Metrics;
import dev.forexsignals.stats.TradeR;
import dev.forexsignals.stats.ValidationGate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Stage 1 verdict rules locked by the owner: < 200 trades INCONCLUSIVE; CI tests judged on the Bonferroni 95% interval. */
class HypothesisVerdictTest {

    static List<TradeR> trades(int n, double r) {
        List<TradeR> out = new ArrayList<>();
        Instant t = Instant.parse("2023-11-20T00:00:00Z");
        for (int i = 0; i < n; i++) out.add(new TradeR("EURUSD", t.plus(Duration.ofHours(70L * i)), t.plus(Duration.ofHours(70L * i + 1)), r, r, r, "X"));
        return out;
    }

    static Validator.RunOutcome outcome(List<TradeR> tr) {
        return new Validator.RunOutcome("x@1", "PARENT", "h", List.of(), tr, Metrics.of(tr), Map.of(), Map.of(), 0, 0, 0, null, 0);
    }

    static final Bootstrap.Ci GOOD = new Bootstrap.Ci(0.3, 0.1, 0.5, 100, 10_000);

    static HypothesisRunner.Variant variant(int n, Bootstrap.Ci adjusted, ValidationGate.Status otherChecks) {
        var o = outcome(trades(n, 0.3));
        List<ValidationGate.Check> checks = new ArrayList<>();
        for (int k = 1; k <= 8; k++) checks.add(new ValidationGate.Check(k, "c" + k, otherChecks, "", ""));
        return new HypothesisRunner.Variant("eurusd_x_conf@1", "h", o, o, o, 0.01, new ValidationGate.Result(otherChecks, checks),
                GOOD, adjusted, GOOD, adjusted);
    }

    @Test
    void everythingPassingWithEnoughTradesIsAStage1Pass() {
        assertThat(HypothesisRunner.stage1Verdict(variant(250, GOOD, ValidationGate.Status.PASS), GOOD, GOOD, new ArrayList<>())).isEqualTo("STAGE1_PASS");
    }

    @Test
    void fewerThan200TradesIsInconclusiveHoweverGoodTheEstimate() {
        List<String> notes = new ArrayList<>();
        assertThat(HypothesisRunner.stage1Verdict(variant(150, GOOD, ValidationGate.Status.PASS), GOOD, GOOD, notes)).isEqualTo("INCONCLUSIVE");
        assertThat(notes).anyMatch(n -> n.contains("150 trades"));
    }

    @Test
    void rawCiAboveZeroButAdjustedCiNotIsAFail() {
        Bootstrap.Ci adjLow = new Bootstrap.Ci(0.3, -0.01, 0.6, 100, 10_000);
        assertThat(HypothesisRunner.stage1Verdict(variant(250, adjLow, ValidationGate.Status.PASS), GOOD, GOOD, new ArrayList<>())).isEqualTo("FAIL");
        assertThat(HypothesisRunner.stage1Verdict(variant(250, GOOD, ValidationGate.Status.PASS), adjLow, GOOD, new ArrayList<>())).as("(a)").isEqualTo("FAIL");
        assertThat(HypothesisRunner.stage1Verdict(variant(250, GOOD, ValidationGate.Status.PASS), GOOD, adjLow, new ArrayList<>())).as("(b)").isEqualTo("FAIL");
    }

    @Test
    void anOrdinaryGateFailIsAFail() {
        assertThat(HypothesisRunner.stage1Verdict(variant(250, GOOD, ValidationGate.Status.FAIL), GOOD, GOOD, new ArrayList<>())).isEqualTo("FAIL");
    }

    @Test
    void adjustedIntervalIsWiderThanRaw() {
        List<TradeR> tr = new ArrayList<>();
        Instant t = Instant.parse("2023-11-20T00:00:00Z");
        for (int i = 0; i < 400; i++) tr.add(new TradeR("EURUSD", t.plus(Duration.ofHours(30L * i)), t.plus(Duration.ofHours(30L * i + 1)), 0, i % 3 == 0 ? 2 : -1, 0, "X"));
        var raw = Bootstrap.meanCi(tr, 10_000, 7, 0.90);
        var adj = Bootstrap.meanCi(tr, 10_000, 7, 0.95);
        assertThat(adj.lo()).isLessThan(raw.lo());
        assertThat(adj.hi()).isGreaterThan(raw.hi());
        assertThat(Bootstrap.meanCi(tr, 10_000, 7)).isEqualTo(raw); // default stays 90%
    }

    @Test
    void stageWindowKeepsOnlySignalsRaisedInsideIt() {
        var st = new HypothesisRunner.Stage("DEVELOPMENT", Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-02-01T00:00:00Z"));
        var o = outcome(List.of());
        assertThat(HypothesisRunner.window(o, st).trades()).isEmpty();
    }
}
