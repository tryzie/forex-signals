package dev.forexsignals.risk;

import dev.forexsignals.paper.CostModel;
import dev.forexsignals.strategy.api.Direction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Cost model (criterion E19) and sizing (criterion F21) for EURUSD, USDJPY, XAUUSD, XAGUSD. */
class CostAndSizingTest {

    private static final Instant LONDON = Instant.parse("2024-03-06T10:00:00Z"); // Wednesday, London open
    private static final Instant ASIA = Instant.parse("2024-03-06T02:00:00Z");

    @Test
    void commissionPerSideInPriceUnits() {
        CostModel c = Rig.costs();
        assertThat(c.commissionPerSideInPrice("EURUSD", 1.08)).isCloseTo(3.0 / 100_000, within(1e-15));     // 0.3 pip
        assertThat(c.commissionPerSideInPrice("USDJPY", 150.0)).isCloseTo(3.0 / 100_000 * 150, within(1e-12)); // 0.0045 JPY
        assertThat(c.commissionPerSideInPrice("XAUUSD", 2300)).isCloseTo(3.5 / 100, within(1e-12));          // $0.035 per oz
        assertThat(c.commissionPerSideInPrice("XAGUSD", 27)).isZero();                                      // spec: rate 0 (pending statement)
    }

    @Test
    void slippageByClassWithSessionAndNewsMultipliersNeverBeyondTimesThree() {
        CostModel c = Rig.costs();
        assertThat(c.slippage("EURUSD", LONDON, false)).isCloseTo(0.00002, within(1e-15));
        assertThat(c.slippage("EURUSD", ASIA, false)).isCloseTo(0.00004, within(1e-15));
        assertThat(c.slippage("EURUSD", ASIA, true)).as("x3, not x6").isCloseTo(0.00006, within(1e-15));
        assertThat(c.slippage("USDJPY", LONDON, false)).isCloseTo(0.002, within(1e-12));
        assertThat(c.slippage("XAUUSD", LONDON, false)).isCloseTo(0.15, within(1e-12));
        assertThat(c.slippage("XAGUSD", LONDON, false)).isCloseTo(0.02, within(1e-12));
        CostModel stressed = new CostModel(Rig.COSTS.stressed(), Rig.SPECS, Rig.spreads(Rig.MEDIANS));
        assertThat(stressed.slippage("EURUSD", LONDON, false)).isCloseTo(0.00004, within(1e-15));
        assertThat(stressed.modelledSpread("EURUSD", LONDON)).isCloseTo(0.00003, within(1e-15)); // +50%
    }

    @Test
    void spreadModelUsesOnlySampleWeeksThatEndedBeforeTheTrade() {
        var samples = new java.util.ArrayList<dev.forexsignals.paper.SpreadModel.Sample>();
        for (String b : new String[]{"ASIA", "LONDON", "OVERLAP", "NEW_YORK", "ROLLOVER"}) {
            samples.add(new dev.forexsignals.paper.SpreadModel.Sample("EURUSD", "2024Q1", Instant.parse("2024-02-11T00:00:00Z"), b, 0.00010, 0.0002, 1));
            samples.add(new dev.forexsignals.paper.SpreadModel.Sample("EURUSD", "2024Q2", Instant.parse("2024-05-12T00:00:00Z"), b, 0.00020, 0.0004, 1));
        }
        var m = new dev.forexsignals.paper.SpreadModel(samples);
        assertThat(m.median("EURUSD", Instant.parse("2024-05-14T10:00:00Z"))).as("Q2 week not finished yet").isEqualTo(0.00010);
        assertThat(m.median("EURUSD", Instant.parse("2024-05-20T10:00:00Z"))).isEqualTo(0.00020);
        assertThat(m.median("EURUSD", Instant.parse("2024-01-10T10:00:00Z"))).as("before any finished week: the first one").isEqualTo(0.00010);
    }

    @Test
    void stressDoublesTheMeasuredFillBiasToo() {
        var withBias = new CostModel(Rig.COSTS.withExtraSlippage(java.util.Map.of("EURUSD", 0.00001)), Rig.SPECS, Rig.spreads(Rig.MEDIANS));
        var stressed = new CostModel(Rig.COSTS.withExtraSlippage(java.util.Map.of("EURUSD", 0.00001)).stressed(), Rig.SPECS, Rig.spreads(Rig.MEDIANS));
        assertThat(withBias.slippage("EURUSD", LONDON, false)).isCloseTo(0.00003, within(1e-15));
        assertThat(stressed.slippage("EURUSD", LONDON, false)).isCloseTo(0.00006, within(1e-15));
    }

    private static RiskGate.Decision size(String symbol, double bid, double ask, double stop, double equity) {
        RiskGate gate = new RiskGate(RiskGate.Config.defaults(), Rig.costs(), null);
        double ref = ask, r = ref - stop;
        var cand = new RiskGate.Candidate("s@1", Rig.CATALOG.get(symbol), Direction.LONG, LONDON, bid, ask, stop, ref + 2 * r, false, true);
        return gate.evaluate(cand, RiskGateTest.book(equity, equity, false, List.of()));
    }

    @Test
    void sizingRisksOnePercentIncludingCommissionAndNeverRoundsUp() {
        // EURUSD: R 0.0015 -> 150 USD/lot + 6 commission = 156; 100/156 = 0.641 -> 0.64 lots.
        var eur = size("EURUSD", 1.07998, 1.08, 1.0785, 10_000).sized();
        assertThat(eur.lots()).isCloseTo(0.64, within(1e-9));
        assertThat(eur.riskUsd()).isCloseTo(0.64 * 156, within(1e-6)).isLessThanOrEqualTo(100);
        // USDJPY at 150: R 0.20 -> 0.20 * 100000 / 150 = 133.33 + 6 = 139.33; 100/139.33 = 0.7177 -> 0.71.
        assertThat(size("USDJPY", 149.997, 150.0, 149.8, 10_000).sized().lots()).isCloseTo(0.71, within(1e-9));
        // XAUUSD: R 15.0 -> 1500 + 7 = 1507; 100/1507 = 0.066 -> 0.06.
        assertThat(size("XAUUSD", 2299.88, 2300.0, 2285.0, 10_000).sized().lots()).isCloseTo(0.06, within(1e-9));
        // XAGUSD: R 0.40 -> 2000 + 0 = 2000; 100/2000 = 0.05.
        assertThat(size("XAGUSD", 26.97, 27.0, 26.6, 10_000).sized().lots()).isCloseTo(0.05, within(1e-9));
        // XAUUSD with a 150 USD stop: 15007 per lot -> 0.0066 lots -> 0.00 < min 0.01 -> SIZE_BELOW_MIN (never rounded up).
        assertThat(size("XAUUSD", 2299.88, 2300.0, 2150.0, 10_000).firstFailure()).isEqualTo(RejectionReason.SIZE_BELOW_MIN);
    }
}
