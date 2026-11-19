package dev.forexsignals.market;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Candle building rules (PLAN §5): closes, gaps, late ticks, weekend, Friday close, DST alignment. */
class CandleBuilderTest {

    private final List<Candle> closed = new ArrayList<>();
    private final List<Tick> late = new ArrayList<>();
    private final CandleBuilder builder = new CandleBuilder("test", PriceBasis.BID, Duration.ofSeconds(2), new CandleBuilder.Listener() {
        @Override public void onCandleClosed(Candle c) { closed.add(c); }
        @Override public void onLateTick(Tick t, Instant b) { late.add(t); }
    });
    private long seq;

    private void tick(String ts, double bid, double ask) {
        builder.onTick(new Tick("test", "EURUSD", Instant.parse(ts), bid, ask, Instant.parse(ts), seq++));
    }

    private List<Candle> of(Timeframe tf) { return closed.stream().filter(c -> c.timeframe() == tf).toList(); }

    @Test
    void m1OhlcAndSpreadFromTicksAndTheBoundaryTickStartsTheNextBar() {
        tick("2024-01-17T10:00:01Z", 1.10000, 1.10010);
        tick("2024-01-17T10:00:20Z", 1.10050, 1.10070);
        tick("2024-01-17T10:00:40Z", 1.09980, 1.09990);
        tick("2024-01-17T10:00:59.999Z", 1.10020, 1.10030);
        assertThat(closed).isEmpty();
        tick("2024-01-17T10:01:00Z", 1.10025, 1.10035); // exactly on the boundary: belongs to 10:01

        assertThat(of(Timeframe.M1)).hasSize(1);
        Candle c = of(Timeframe.M1).getFirst();
        assertThat(c.openTime()).isEqualTo(Instant.parse("2024-01-17T10:00:00Z"));
        assertThat(c.closeTime()).isEqualTo(Instant.parse("2024-01-17T10:01:00Z"));
        assertThat(new double[]{c.open(), c.high(), c.low(), c.close()}).containsExactly(1.10000, 1.10050, 1.09980, 1.10020);
        assertThat(c.tickCount()).isEqualTo(4);
        assertThat(c.spreadMin()).isCloseTo(0.0001, within(1e-9));
        assertThat(c.spreadMed()).isCloseTo(0.0001, within(1e-9));   // sorted .0001 .0001 .0001 .0002
        assertThat(c.spreadMax()).isCloseTo(0.0002, within(1e-9));
        assertThat(c.source()).isEqualTo(Candle.Source.BUILT);
    }

    @Test
    void quietMarketClosesAfterGraceAndLaterTicksForThatBarAreLateNotApplied() {
        tick("2024-01-17T10:00:10Z", 1.1, 1.1001);
        builder.onTime(Instant.parse("2024-01-17T10:01:01.999Z"));
        assertThat(closed).as("still inside the 2 s grace").isEmpty();
        builder.onTime(Instant.parse("2024-01-17T10:01:02Z"));
        assertThat(of(Timeframe.M1)).hasSize(1);

        tick("2024-01-17T10:00:59Z", 1.2, 1.2001); // arrives after the bar closed
        assertThat(late).hasSize(1);
        assertThat(builder.lateTicks("EURUSD")).isEqualTo(1);
        assertThat(of(Timeframe.M1).getFirst().high()).as("never repainted").isEqualTo(1.1);
        builder.flush();
        assertThat(of(Timeframe.M1)).as("the late tick did not start a bar").hasSize(1);
    }

    @Test
    void tickOlderThanTheFormingBarIsLate() {
        tick("2024-01-17T10:02:10Z", 1.1, 1.1001);
        tick("2024-01-17T10:01:30Z", 1.3, 1.3001);
        assertThat(late).extracting(Tick::bid).containsExactly(1.3);
        builder.flush();
        assertThat(of(Timeframe.M1)).hasSize(1);
        assertThat(of(Timeframe.M1).getFirst().high()).isEqualTo(1.1);
    }

    @Test
    void outOfOrderTicksInsideOneMinuteUseTimestampsForOpenAndClose() {
        tick("2024-01-17T10:00:30Z", 1.105, 1.1051);
        tick("2024-01-17T10:00:10Z", 1.101, 1.1011); // earlier timestamp, arrived later
        builder.flush();
        Candle c = of(Timeframe.M1).getFirst();
        assertThat(c.open()).isEqualTo(1.101);
        assertThat(c.close()).isEqualTo(1.105);
        assertThat(late).isEmpty();
    }

    @Test
    void intraSessionGapProducesNoCandleAndDerivedBarsSpanOnlyRealMinutes() {
        tick("2024-01-17T10:00:05Z", 1.1000, Double.NaN);
        tick("2024-01-17T10:01:05Z", 1.1010, Double.NaN);
        // no tick at all in 10:02
        tick("2024-01-17T10:03:05Z", 1.0990, Double.NaN);
        tick("2024-01-17T10:04:05Z", 1.1005, Double.NaN);
        tick("2024-01-17T10:05:00.500Z", 1.1006, Double.NaN);

        assertThat(of(Timeframe.M1)).extracting(c -> c.openTime().toString())
                .containsExactly("2024-01-17T10:00:00Z", "2024-01-17T10:01:00Z", "2024-01-17T10:03:00Z", "2024-01-17T10:04:00Z");
        Candle m5 = of(Timeframe.M5).getFirst();
        assertThat(m5.openTime()).isEqualTo(Instant.parse("2024-01-17T10:00:00Z"));
        assertThat(new double[]{m5.open(), m5.high(), m5.low(), m5.close()}).containsExactly(1.1000, 1.1010, 1.0990, 1.1005);
        assertThat(m5.tickCount()).isEqualTo(4);
        assertThat(Double.isNaN(m5.spreadMed())).as("no ask -> no spread").isTrue();

        // A long silent stretch: the next tick is at 10:20. No M5 is invented for 10:05-10:20 except the bar that had a tick.
        tick("2024-01-17T10:20:01Z", 1.1001, Double.NaN);
        assertThat(of(Timeframe.M5)).extracting(c -> c.openTime().toString())
                .containsExactly("2024-01-17T10:00:00Z", "2024-01-17T10:05:00Z");
    }

    @Test
    void weekendGapProducesNoWeekendCandlesAndFridayBarsCloseAt1700NewYork() {
        // Friday 19 Jan 2024, 16:59:30 EST = 21:59:30Z: last ticks of the week.
        tick("2024-01-19T21:59:30Z", 1.0890, 1.0891);
        tick("2024-01-19T21:59:59.999Z", 1.0892, 1.0893);

        // Friday close: the clock passes 17:00 NY + grace; D1 (Thu 17:00 to Fri 17:00 NY) and H4 close without any new tick.
        builder.onTime(Instant.parse("2024-01-19T22:00:02Z"));
        Candle d1 = of(Timeframe.D1).getFirst();
        assertThat(d1.openTime()).isEqualTo(Instant.parse("2024-01-18T22:00:00Z"));
        assertThat(d1.closeTime()).isEqualTo(Instant.parse("2024-01-19T22:00:00Z"));
        assertThat(d1.close()).isEqualTo(1.0892);
        assertThat(of(Timeframe.H4).getFirst().openTime()).isEqualTo(Instant.parse("2024-01-19T18:00:00Z")); // 13:00 NY

        // Weekend: time passes, no ticks, nothing is built.
        int before = closed.size();
        builder.onTime(Instant.parse("2024-01-21T21:00:00Z"));
        assertThat(closed).hasSize(before);

        // Sunday open 17:00 EST = 22:00Z.
        tick("2024-01-21T22:00:05Z", 1.0880, 1.0883);
        builder.flush();
        assertThat(closed).noneMatch(c -> c.openTime().isAfter(Instant.parse("2024-01-19T22:00:00Z"))
                && c.openTime().isBefore(Instant.parse("2024-01-21T22:00:00Z")));
        assertThat(of(Timeframe.D1)).extracting(c -> c.openTime().toString())
                .containsExactly("2024-01-18T22:00:00Z", "2024-01-21T22:00:00Z");
    }

    @Test
    void h4BarsFollowNewYorkDstWhileLondonHasNotSwitched() {
        // 11-29 March 2024: New York is on EDT, London still on GMT. H4 opens at 09:00 NY = 13:00Z.
        tick("2024-03-20T12:59:59Z", 1.0850, 1.0851);
        tick("2024-03-20T13:00:00Z", 1.0860, 1.0861);
        tick("2024-03-20T16:59:59Z", 1.0870, 1.0871);
        tick("2024-03-20T17:00:00Z", 1.0880, 1.0881);
        assertThat(of(Timeframe.H4)).extracting(c -> c.openTime() + "->" + c.closeTime())
                .containsExactly("2024-03-20T09:00:00Z->2024-03-20T13:00:00Z", "2024-03-20T13:00:00Z->2024-03-20T17:00:00Z");
    }

    @Test
    void h4BarsUseStandardTimeAgainAfterNewYorkFallsBack() {
        // 6 Nov 2024: both zones on standard time. H4 opens at 09:00 EST = 14:00Z.
        tick("2024-11-06T14:00:00Z", 1.0710, 1.0711);
        tick("2024-11-06T18:00:00Z", 1.0720, 1.0721);
        assertThat(of(Timeframe.H4)).extracting(c -> c.openTime().toString()).containsExactly("2024-11-06T14:00:00Z");
    }

    @Test
    void derivedBarsFromTicksEqualDerivedBarsFromImportedM1() {
        // Same code path check: feeding the M1 candles back through onClosedM1 must reproduce every
        // derived candle exactly (this is what the history importer relies on).
        Random r = new Random(42);
        Instant t = Instant.parse("2024-03-07T00:00:00Z");
        double bid = 1.09;
        while (t.isBefore(Instant.parse("2024-03-12T00:00:00Z"))) {
            t = t.plusMillis(200 + r.nextInt(20_000));
            if (!TradingHours.FX.isOpen(t)) continue;
            if (r.nextInt(500) == 0) t = t.plusSeconds(600); // occasional quiet stretch
            bid += (r.nextGaussian() * 0.00005);
            builder.onTick(new Tick("test", "EURUSD", t, round5(bid), round5(bid + 0.00008 + r.nextInt(3) * 0.00001), t, seq++));
            if (r.nextInt(50) == 0) builder.onTime(t.plusSeconds(r.nextInt(5)));
        }
        builder.flush();

        List<Candle> fromTicks = closed.stream().filter(c -> c.timeframe() != Timeframe.M1).toList();
        List<Candle> m1s = of(Timeframe.M1);
        List<Candle> fromM1 = new ArrayList<>();
        CandleBuilder second = new CandleBuilder("test", PriceBasis.BID, Duration.ofSeconds(2), fromM1::add);
        m1s.forEach(second::onClosedM1);
        second.flush();

        assertThat(m1s.size()).isGreaterThan(3000);
        assertThat(fromM1).hasSameSizeAs(fromTicks);
        assertThat(sortKey(fromM1)).isEqualTo(sortKey(fromTicks));
    }

    private static double round5(double v) { return Math.round(v * 1e5) / 1e5; }

    private static List<String> sortKey(List<Candle> cs) {
        return cs.stream().map(c -> c.timeframe() + " " + c.openTime() + " " + c.open() + " " + c.high() + " " + c.low() + " "
                + c.close() + " " + c.tickCount() + " " + c.spreadMin() + " " + c.spreadMed() + " " + c.spreadMax()).sorted().toList();
    }
}
