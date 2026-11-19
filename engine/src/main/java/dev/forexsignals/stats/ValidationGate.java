package dev.forexsignals.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The validation gate (PLAN §8, as approved). All values are after costs. Each check is PASS, FAIL or INCONCLUSIVE;
 * the verdict is FAIL if any check fails, otherwise INCONCLUSIVE if any is inconclusive, otherwise PASS.
 *  1 sample        >= 200 trades over >= 12 months covering a trending and a ranging regime (else INCONCLUSIVE)
 *  2 expectancy    mean >= +0.10R and the bootstrap 90% CI lower bound > 0
 *  3 drawdown      max drawdown of the cumulative R curve <= 10R
 *  4 profit factor >= 1.2
 *  5 walk-forward  pooled OOS > 0 and >= 50% of IS with >= 50 OOS trades (INCONCLUSIVE otherwise; WITHHELD = INCONCLUSIVE
 *                  when the parameters are not frozen or changed after the freeze)
 *  6 cost stress   expectancy with spread x1.5 and slippage x2 still > 0
 *  7 concentration no symbol > 50% of the positive R
 *  8 beats chance  bootstrap 90% CI lower bound of (strategy - random control) > 0
 */
public final class ValidationGate {

    public enum Status { PASS, FAIL, INCONCLUSIVE }

    public record Check(int number, String name, Status status, String observed, String rule) {}

    public record Result(Status verdict, List<Check> checks) {}

    public record Inputs(Metrics metrics, Bootstrap.Ci ci, boolean regimesCovered, String regimeNote, WalkForward.Result walkForward,
                         boolean oosAllowed, String oosWithheldReason, Metrics stressed, Bootstrap.Ci vsControl) {}

    private ValidationGate() {}

    public static Result evaluate(Inputs in) {
        Metrics m = in.metrics();
        List<Check> c = new ArrayList<>();
        boolean enough = m.trades() >= 200 && m.months() >= 12 && in.regimesCovered();
        c.add(new Check(1, "sample", enough ? Status.PASS : Status.INCONCLUSIVE,
                m.trades() + " trades over " + String.format("%.1f", m.months()) + " months; regimes: " + in.regimeNote(), ">= 200 trades, >= 12 months, trending + ranging"));
        boolean exp = m.expectancy() >= 0.10 && in.ci().lo() > 0;
        c.add(new Check(2, "expectancy", m.trades() == 0 ? Status.INCONCLUSIVE : exp ? Status.PASS : Status.FAIL,
                String.format("%.3fR (90%% CI %.3f..%.3f)", m.expectancy(), in.ci().lo(), in.ci().hi()), "mean >= 0.10R and CI low > 0"));
        c.add(new Check(3, "drawdown", m.trades() == 0 ? Status.INCONCLUSIVE : m.maxDrawdownR() <= 10 ? Status.PASS : Status.FAIL,
                String.format("%.2fR", m.maxDrawdownR()), "<= 10R"));
        c.add(new Check(4, "profit factor", m.trades() == 0 ? Status.INCONCLUSIVE : m.profitFactor() >= 1.2 ? Status.PASS : Status.FAIL,
                String.format("%.2f", m.profitFactor()), ">= 1.2"));
        if (!in.oosAllowed()) {
            c.add(new Check(5, "walk-forward", Status.INCONCLUSIVE, "OOS WITHHELD: " + in.oosWithheldReason(), "frozen params; OOS > 0, >= 50% IS, >= 50 trades"));
        } else {
            WalkForward.Result w = in.walkForward();
            c.add(new Check(5, "walk-forward", Status.valueOf(w.verdict()),
                    String.format("OOS %.3fR over %d trades vs IS %.3fR", w.oosExpectancy(), w.oosTrades(), w.isExpectancy()), "OOS > 0, >= 50% of IS, >= 50 OOS trades"));
        }
        c.add(new Check(6, "cost stress", in.stressed().trades() == 0 ? Status.INCONCLUSIVE : in.stressed().expectancy() > 0 ? Status.PASS : Status.FAIL,
                String.format("%.3fR with spread x1.5, slippage x2", in.stressed().expectancy()), "> 0"));
        double maxShare = m.positiveShareBySymbol().values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        c.add(new Check(7, "concentration", m.trades() == 0 ? Status.INCONCLUSIVE : maxShare <= 0.5 ? Status.PASS : Status.FAIL,
                String.format("max symbol share %.0f%% %s", 100 * maxShare, m.positiveShareBySymbol()), "<= 50% of positive R"));
        Bootstrap.Ci v = in.vsControl();
        c.add(new Check(8, "beats chance", v == null || Double.isNaN(v.lo()) ? Status.INCONCLUSIVE : v.lo() > 0 ? Status.PASS : Status.FAIL,
                v == null ? "no control" : String.format("diff %.3fR (90%% CI %.3f..%.3f)", v.mean(), v.lo(), v.hi()), "CI low of (strategy - control) > 0"));
        Status verdict = c.stream().anyMatch(k -> k.status() == Status.FAIL) ? Status.FAIL
                : c.stream().anyMatch(k -> k.status() == Status.INCONCLUSIVE) ? Status.INCONCLUSIVE : Status.PASS;
        return new Result(verdict, List.copyOf(c));
    }

    public static Map<String, Object> asMap(Result r) {
        return Map.of("verdict", r.verdict().name(), "checks", r.checks());
    }
}
