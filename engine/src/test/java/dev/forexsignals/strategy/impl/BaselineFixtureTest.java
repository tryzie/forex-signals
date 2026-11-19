package dev.forexsignals.strategy.impl;

import dev.forexsignals.market.Timeframe;
import dev.forexsignals.strategy.Series;
import dev.forexsignals.strategy.StrategyRunner;
import dev.forexsignals.strategy.api.Direction;
import dev.forexsignals.strategy.api.Reason;
import dev.forexsignals.strategy.api.SignalProposal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trigger / no-trigger fixtures for the three baselines (Phase 2 criterion D11). Each fixture is a hand-shaped
 * multi-timeframe series ending at one candle close; the last batch decides.
 */
class BaselineFixtureTest {

    private static List<double[]> plus(List<double[]> a, double[]... more) {
        List<double[]> out = new ArrayList<>(a);
        out.addAll(List.of(more));
        return out;
    }

    /** A bar from open, close and two extremes (in any order): high/low are normalised. */
    private static double[] bar(double open, double close, double x1, double x2) {
        double hi = Math.max(Math.max(open, close), Math.max(x1, x2)), lo = Math.min(Math.min(open, close), Math.min(x1, x2));
        return new double[]{open, hi, lo, close};
    }

    private static List<SignalProposal> proposals(StrategyRunner.Result r) {
        return r.proposals().stream().map(StrategyRunner.Proposal::proposal).toList();
    }

    // ------------------------------------------------------------------ (a) EMA pullback

    /** Uptrend on all timeframes; on M15 a pullback bar dips through EMA20, then a bullish bar closes back above it. */
    private static Series emaSeries(double sign, boolean pullback, boolean bullishTrigger, double h4Sign) {
        List<double[]> m15 = Series.trend(170, 1.10, sign * 0.0001, 0.00005);
        double p = Series.last(m15);
        if (pullback) m15 = plus(m15, bar(p, p - sign * 0.0005, p + sign * 0.0001, p - sign * 0.0015));
        else m15 = plus(m15, bar(p, p + sign * 0.0001, p + sign * 0.00015, p + sign * 0.00005));
        double q = Series.last(m15);
        double close = bullishTrigger ? q + sign * 0.0008 : q - sign * 0.0001;
        m15 = plus(m15, bar(q, close, q - sign * 0.0001, close + sign * 0.0001));
        return new Series("EURUSD")
                .add(Timeframe.H4, Series.trend(170, 1.00, h4Sign * 0.001, 0.0002))
                .add(Timeframe.H1, Series.trend(170, 1.05, sign * 0.0003, 0.0001))
                .add(Timeframe.M15, m15);
    }

    @Test
    void emaPullbackLongTriggersWithAllReasonsPassed() {
        List<SignalProposal> p = proposals(emaSeries(1, true, true, 1).run(new EmaPullback()));
        assertThat(p).singleElement().satisfies(s -> {
            assertThat(s.direction()).isEqualTo(Direction.LONG);
            assertThat(s.reasons()).hasSize(5).allMatch(Reason::passed);
            assertThat(s.targetR()).isEqualTo(2.0);
            assertThat(s.timeStopBars()).isEqualTo(32);
            assertThat(s.stop().atrMultiple()).isEqualTo(1.5);
        });
    }

    @Test
    void emaPullbackShortIsTheMirror() {
        assertThat(proposals(emaSeries(-1, true, true, -1).run(new EmaPullback()))).singleElement()
                .extracting(SignalProposal::direction).isEqualTo(Direction.SHORT);
    }

    @Test
    void emaPullbackNeedsThePullbackTheTriggerCandleAndTheTrendContext() {
        assertThat(proposals(emaSeries(1, false, true, 1).run(new EmaPullback()))).as("no pullback to EMA20").isEmpty();
        assertThat(proposals(emaSeries(1, true, false, 1).run(new EmaPullback()))).as("trigger candle bearish").isEmpty();
        assertThat(proposals(emaSeries(1, true, true, -1).run(new EmaPullback()))).as("H4 trend against").isEmpty();
    }

    // ------------------------------------------------------------------ (b) Donchian breakout

    private static Series donchianSeries(double h4Sign, double breakoutSize, boolean volSpike) {
        List<double[]> m15 = Series.chop(330, 1.10, 0.0003);
        if (volSpike) m15 = plus(m15.subList(0, 300), Series.chop(30, 1.10, 0.0030).toArray(new double[0][]));
        double p = Series.last(m15);
        double close = 1.10 + breakoutSize;
        m15 = plus(m15, new double[]{p, close + 0.0001, Math.min(p, close) - 0.0001, close});
        return new Series("EURUSD").add(Timeframe.H4, Series.trend(170, 1.00, h4Sign * 0.001, 0.0002)).add(Timeframe.M15, m15);
    }

    @Test
    void donchianBreakoutLongTriggersAboveThePrevious20BarsWithAStructureStop() {
        List<SignalProposal> p = proposals(donchianSeries(1, 0.0010, false).run(new DonchianBreakout()));
        assertThat(p).singleElement().satisfies(s -> {
            assertThat(s.direction()).isEqualTo(Direction.LONG);
            assertThat(s.stop().structureLevel()).as("10-bar low").isLessThan(1.10);
            assertThat(s.stop().minAtr()).isEqualTo(1.0);
            assertThat(s.stop().maxAtr()).isEqualTo(1.5);
            assertThat(s.memo()).containsKey("broken");
            assertThat(s.memo().get("broken")).isLessThan(1.1010).isGreaterThan(1.10);
        });
    }

    @Test
    void donchianNeedsTrendContextARealBreakoutAndNormalVolatility() {
        assertThat(proposals(donchianSeries(-1, 0.0010, false).run(new DonchianBreakout()))).as("H4 against").isEmpty();
        assertThat(proposals(donchianSeries(1, 0.0002, false).run(new DonchianBreakout()))).as("close inside the channel").isEmpty();
        assertThat(proposals(donchianSeries(1, 0.0010, true).run(new DonchianBreakout()))).as("ATR14/ATR100 above 2.0").isEmpty();
    }

    // ------------------------------------------------------------------ (c) RSI mean reversion

    private static Series rsiSeries(boolean h1Ranging, double bounce) {
        List<double[]> m15 = plus(Series.chop(60, 1.10, 0.0002), Series.trend(12, 1.10, -0.0004, 0.00005).toArray(new double[0][]));
        double p = Series.last(m15);
        m15 = plus(m15, new double[]{p, p + bounce + 0.0001, p - 0.0001, p + bounce});
        return new Series("EURUSD")
                .add(Timeframe.H1, h1Ranging ? Series.chop(60, 1.10, 0.0005) : Series.trend(60, 1.05, 0.001, 0.0001))
                .add(Timeframe.M15, m15);
    }

    @Test
    void rsiCrossBackAbove30InARangeTriggersLong() {
        assertThat(proposals(rsiSeries(true, 0.0040).run(new RsiMeanReversion()))).singleElement().satisfies(s -> {
            assertThat(s.direction()).isEqualTo(Direction.LONG);
            assertThat(s.targetR()).isEqualTo(1.5);
            assertThat(s.timeStopBars()).isEqualTo(16);
        });
    }

    @Test
    void rsiNeedsARangeRegimeAndAnActualCross() {
        assertThat(proposals(rsiSeries(false, 0.0040).run(new RsiMeanReversion()))).as("H1 trending: ADX >= 20").isEmpty();
        assertThat(proposals(rsiSeries(true, 0.0001).run(new RsiMeanReversion()))).as("RSI still below 30").isEmpty();
    }

    @Test
    void rsiNeverTradesMetals() {
        assertThat(new RsiMeanReversion().meta().symbols()).doesNotContain("XAUUSD", "XAGUSD", "USDX");
    }
}
