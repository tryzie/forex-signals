package dev.forexsignals.risk;

import dev.forexsignals.market.SymbolCatalog;
import dev.forexsignals.strategy.api.Direction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 2 criterion F20: one test per rejection reason (PLAN §9), each changing one input of an otherwise valid signal. */
class RiskGateTest {

    static final Instant LONDON = Instant.parse("2024-03-06T10:00:00Z"); // Wednesday 10:00 UTC: London open, no NY

    static RiskGate.Book book(double equity, double dayStart, boolean kill, List<RiskGate.Exposure> open) {
        return new RiskGate.Book() {
            @Override public double equity() { return equity; }
            @Override public double dayStartEquity() { return dayStart; }
            @Override public boolean killSwitchTripped() { return kill; }
            @Override public List<RiskGate.Exposure> open() { return open; }
        };
    }

    static final RiskGate.Book OK_BOOK = book(10_000, 10_000, false, List.of());

    /** EURUSD long, 15-pip stop, 2R target, 0.2-pip spread, London session: approved. */
    static RiskGate.Candidate valid() { return eur(Direction.LONG, LONDON, 1.07998, 1.08, 1.0785, 1.0830); }

    static RiskGate.Candidate eur(Direction d, Instant at, double bid, double ask, double stop, double target) {
        return new RiskGate.Candidate("s@1", Rig.CATALOG.get("EURUSD"), d, at, bid, ask, stop, target, false, true);
    }

    static RiskGate gate() { return gate(RiskGate.Config.defaults(), null); }

    static RiskGate gate(RiskGate.Config cfg, NewsCalendar news) { return new RiskGate(cfg, Rig.costs(), news); }

    static RejectionReason first(RiskGate.Candidate c) { return gate().evaluate(c, OK_BOOK).firstFailure(); }

    static RiskGate.Exposure exp(String sym, Direction d) { return new RiskGate.Exposure(Rig.CATALOG.get(sym), d); }

    @Test
    void validSignalIsApprovedAllChecksRecordedAndShadow() {
        RiskGate.Decision d = gate().evaluate(valid(), OK_BOOK);
        assertThat(d.isApproved()).isTrue();
        assertThat(d.checks()).extracting(RiskGate.Check::reason).contains(RejectionReason.values());
        assertThat(d.approved().deliver()).as("STRATEGY_NOT_LIVE: shadow, never delivered").isFalse();
    }

    @Test void noStop() { assertThat(first(eur(Direction.LONG, LONDON, 1.07998, 1.08, 1.0810, 1.0830))).isEqualTo(RejectionReason.NO_STOP); }

    @Test void rrTooLow() { assertThat(first(eur(Direction.LONG, LONDON, 1.07998, 1.08, 1.0785, 1.0820))).isEqualTo(RejectionReason.RR_TOO_LOW); }

    @Test void rrOfExactly1point5Passes() { assertThat(first(eur(Direction.LONG, LONDON, 1.07998, 1.08, 1.0785, 1.08225))).isNull(); }

    @Test void costVsStop() {
        // 2-pip stop: spread 0.2 + commission 0.6 + slippage 0.4 = 1.2 pips = 0.6R > 0.15R.
        assertThat(first(eur(Direction.LONG, LONDON, 1.07998, 1.08, 1.0798, 1.0806))).isEqualTo(RejectionReason.COST_VS_STOP);
    }

    @Test void spreadCap() {
        // 1 pip spread vs 0.2 pip median (cap 0.5 pip); a 100-pip stop keeps the cost check clear.
        assertThat(first(eur(Direction.LONG, LONDON, 1.0799, 1.08, 1.07, 1.10))).isEqualTo(RejectionReason.SPREAD_CAP);
    }

    @Test void outsideSession() {
        assertThat(first(eur(Direction.LONG, Instant.parse("2024-03-06T03:00:00Z"), 1.07998, 1.08, 1.0785, 1.0830)))
                .isEqualTo(RejectionReason.OUTSIDE_SESSION);
        assertThat(first(eur(Direction.LONG, Instant.parse("2024-03-08T20:30:00Z"), 1.07998, 1.08, 1.0785, 1.0830)))
                .as("Friday after 15:00 NY").isEqualTo(RejectionReason.OUTSIDE_SESSION);
    }

    @Test void newsBlackout() {
        NewsCalendar cal = new NewsCalendar(List.of(new NewsCalendar.Event(LONDON.plusSeconds(20 * 60), "EUR", "ECB", "MAJOR", "", "")),
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(gate(RiskGate.Config.defaults(), cal).evaluate(valid(), OK_BOOK).firstFailure()).isEqualTo(RejectionReason.NEWS_BLACKOUT);
        NewsCalendar jpy = new NewsCalendar(List.of(new NewsCalendar.Event(LONDON, "JPY", "BoJ", "MAJOR", "", "")),
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(gate(RiskGate.Config.defaults(), jpy).evaluate(valid(), OK_BOOK).isApproved()).as("JPY news does not hit EURUSD").isTrue();
    }

    @Test void newsBlackoutFallbackWhenTheCalendarDoesNotCoverTheTime() {
        NewsCalendar stale = new NewsCalendar(List.of(), Instant.parse("2023-01-01T00:00:00Z"), Instant.parse("2023-02-01T00:00:00Z"));
        // 08:30 London on a weekday: inside the EUR/GBP fallback window 07:00-09:30 London.
        var c = eur(Direction.LONG, Instant.parse("2024-03-06T08:30:00Z"), 1.07998, 1.08, 1.0785, 1.0830);
        assertThat(gate(RiskGate.Config.defaults(), stale).evaluate(c, OK_BOOK).firstFailure()).isEqualTo(RejectionReason.NEWS_BLACKOUT_FALLBACK);
    }

    @Test void dailyLossCap() {
        assertThat(gate().evaluate(valid(), book(9_700, 10_000, false, List.of())).firstFailure()).isEqualTo(RejectionReason.DAILY_LOSS_CAP);
        assertThat(gate().evaluate(valid(), book(9_701, 10_000, false, List.of())).isApproved()).isTrue();
    }

    @Test void killSwitch() {
        assertThat(gate().evaluate(valid(), book(10_000, 10_000, true, List.of())).firstFailure()).isEqualTo(RejectionReason.KILL_SWITCH);
    }

    @Test void maxOpen() {
        var open = List.of(exp("USDJPY", Direction.LONG), exp("XAUUSD", Direction.SHORT), exp("GBPUSD", Direction.SHORT));
        assertThat(gate().evaluate(valid(), book(10_000, 10_000, false, open)).firstFailure()).isEqualTo(RejectionReason.MAX_OPEN);
    }

    @Test void currencyExposureCountsMetalsAsOneCluster() {
        // Two METALS longs open; a third METALS long would make 3 in the same direction.
        var open = List.of(exp("XAUUSD", Direction.LONG), exp("XAGUSD", Direction.LONG));
        var xau = new RiskGate.Candidate("s@1", Rig.CATALOG.get("XAUUSD"), Direction.LONG, LONDON, 2299.88, 2300.0, 2285, 2330, false, true);
        RiskGate.Decision d = gate().evaluate(xau, book(10_000, 10_000, false, open));
        assertThat(d.firstFailure()).isEqualTo(RejectionReason.CURRENCY_EXPOSURE);
        assertThat(RiskGate.legs(List.of(exp("XAUUSD", Direction.LONG), exp("XAGUSD", Direction.LONG)))).containsEntry("METALS+", 2);
    }

    @Test void usdNetExposure() {
        // With the default limits USD_NET (|net| <= 2) is implied by the per-currency limit (USD legs <= 2 per side),
        // so it is tested with maxNetUsd = 1: EURUSD long + GBPUSD long = 2 short-USD legs, net +2 > 1.
        var cfg = new RiskGate.Config(1.0, 3.0, 10.0, 3, 1.5, 2, 1, 0.15, 2.5);
        var gbp = new RiskGate.Candidate("s@1", Rig.CATALOG.get("GBPUSD"), Direction.LONG, LONDON, 1.26997, 1.27, 1.2685, 1.2730, false, true);
        assertThat(gate(cfg, null).evaluate(gbp, book(10_000, 10_000, false, List.of(exp("EURUSD", Direction.LONG)))).firstFailure())
                .isEqualTo(RejectionReason.USD_NET_EXPOSURE);
        assertThat(RiskGate.usdNet(List.of(exp("EURUSD", Direction.LONG), exp("USDJPY", Direction.LONG)))).isZero();
    }

    @Test void feedUnhealthy() {
        var c = new RiskGate.Candidate("s@1", Rig.CATALOG.get("EURUSD"), Direction.LONG, LONDON, 1.07998, 1.08, 1.0785, 1.0830, false, false);
        assertThat(first(c)).isEqualTo(RejectionReason.FEED_UNHEALTHY);
    }

    @Test void contextOnlySymbolIsNeverTraded() {
        SymbolCatalog.SymbolDef usdx = Rig.CATALOG.get("USDX");
        var c = new RiskGate.Candidate("s@1", usdx, Direction.LONG, LONDON, 104.00, 104.05, 60.0, 190.0, false, true); // wide stop, 2R target: isolates the role check
        RiskGate.Decision d = gate().evaluate(c, OK_BOOK);
        assertThat(d.isApproved()).isFalse();
        assertThat(d.checks()).filteredOn(k -> k.reason() == RejectionReason.CONTEXT_ONLY_SYMBOL).singleElement().extracting(RiskGate.Check::passed).isEqualTo(false);
        assertThat(d.firstFailure()).isEqualTo(RejectionReason.CONTEXT_ONLY_SYMBOL);
    }

    @Test void sizeBelowMin() {
        assertThat(gate().evaluate(valid(), book(50, 50, false, List.of())).firstFailure()).isEqualTo(RejectionReason.SIZE_BELOW_MIN);
    }

    @Test void strategyNotLiveOnlyStopsDelivery() {
        var live = new RiskGate.Candidate("s@1", Rig.CATALOG.get("EURUSD"), Direction.LONG, LONDON, 1.07998, 1.08, 1.0785, 1.0830, true, true);
        assertThat(gate().evaluate(live, OK_BOOK).approved().deliver()).isTrue();
        assertThat(gate().evaluate(valid(), OK_BOOK).approved().deliver()).isFalse();
        assertThat(gate().evaluate(valid(), OK_BOOK).isApproved()).isTrue();
    }

    @Test void allFifteenReasonsHaveATest() {
        List<String> tested = new ArrayList<>(List.of("NO_STOP", "RR_TOO_LOW", "COST_VS_STOP", "SPREAD_CAP", "OUTSIDE_SESSION", "NEWS_BLACKOUT",
                "NEWS_BLACKOUT_FALLBACK", "DAILY_LOSS_CAP", "KILL_SWITCH", "MAX_OPEN", "CURRENCY_EXPOSURE", "USD_NET_EXPOSURE", "FEED_UNHEALTHY",
                "CONTEXT_ONLY_SYMBOL", "SIZE_BELOW_MIN", "STRATEGY_NOT_LIVE"));
        assertThat(java.util.Arrays.stream(RejectionReason.values()).map(Enum::name)).containsExactlyInAnyOrderElementsOf(tested);
    }
}
