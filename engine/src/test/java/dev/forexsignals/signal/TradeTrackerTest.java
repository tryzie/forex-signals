package dev.forexsignals.signal;

import dev.forexsignals.market.Tick;
import dev.forexsignals.risk.Rig;
import dev.forexsignals.risk.RiskGate;
import dev.forexsignals.strategy.api.Direction;
import dev.forexsignals.strategy.api.SignalProposal;
import dev.forexsignals.strategy.api.StopRule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Phase 2 criterion F22: tick-level tracker behaviour. EURUSD long, reference ask 1.08000, stop 1.0785 (R = 15 pips), target 1.0830. */
class TradeTrackerTest {

    static final Instant T = Instant.parse("2024-03-06T10:00:00Z"); // Wednesday, London session
    private final TradeTracker tracker = new TradeTracker(Rig.costs(), null, Duration.ofSeconds(10), null);
    private long seq;

    private Signal signal(Direction d, double bid, double ask, double stop, double target) {
        RiskGate gate = new RiskGate(RiskGate.Config.defaults(), Rig.costs(), null);
        var c = new RiskGate.Candidate("s@1", Rig.CATALOG.get("EURUSD"), d, T, bid, ask, stop, target, false, true);
        var dec = gate.evaluate(c, new RiskGate.Book() {
            public double equity() { return 10_000; }
            public double dayStartEquity() { return 10_000; }
            public boolean killSwitchTripped() { return false; }
            public List<RiskGate.Exposure> open() { return List.of(); }
        });
        assertThat(dec.isApproved()).as(String.valueOf(dec.firstFailure())).isTrue();
        var p = new SignalProposal(d, StopRule.atrMultiple(0.001, 1.5), 2.0, 32, List.of(), "", "", Map.of());
        return tracker.register(dec.approved(), "s", "1", "hash", p, stop, target);
    }

    private Signal longSignal() { return signal(Direction.LONG, 1.07998, 1.08000, 1.0785, 1.0830); }

    private void tick(Instant at, double bid, double ask) { tracker.onTick(new Tick("t", "EURUSD", at, bid, ask, at, seq++)); }

    @Test
    void fillsAtTheFirstTickAfterLatencyAtAskPlusSlippageNeverAtTheClose() {
        Signal s = longSignal();
        tick(T.plusSeconds(5), 1.07990, 1.07992);
        assertThat(s.state).as("inside the 10 s latency").isEqualTo(Signal.State.PENDING);
        tick(T.plusSeconds(10), 1.07999, 1.08001);
        assertThat(s.state).isEqualTo(Signal.State.ACTIVE);
        assertThat(s.fill).isCloseTo(1.08001 + 0.00002, within(1e-12));
        assertThat(s.filledAt).isEqualTo(T.plusSeconds(10));
    }

    @Test
    void targetIsFilledExactlyAtTheLevelAndNetRIsAfterCosts() {
        Signal s = longSignal();
        tick(T.plusSeconds(10), 1.07999, 1.08001);
        tick(T.plusSeconds(600), 1.08310, 1.08312);
        assertThat(s.closeReason).isEqualTo(Signal.CloseReason.TARGET);
        assertThat(s.close).isEqualTo(1.0830);
        // net = (1.0830 - 1.08003 - 2 * 0.00003 commission) / 0.0015
        assertThat(s.netR).isCloseTo((1.0830 - 1.08003 - 0.00006) / 0.0015, within(1e-9));
        // gross = mid at exit (1.08311) - mid at the alert (1.07999), over R
        assertThat(s.grossR).isCloseTo((1.08311 - 1.07999) / 0.0015, within(1e-9));
        assertThat(s.grossR).isGreaterThan(s.netR);
        assertThat(s.pnlUsd).isCloseTo(s.lots * 100_000 * (1.0830 - 1.08003) - s.commissionUsd, within(1e-6));
    }

    @Test
    void gapThroughTheStopFillsAtTheWorsePrice() {
        Signal s = longSignal();
        tick(T.plusSeconds(10), 1.07999, 1.08001);
        tick(T.plusSeconds(60), 1.07800, 1.07802); // gapped 5 pips below the stop
        assertThat(s.closeReason).isEqualTo(Signal.CloseReason.STOP);
        assertThat(s.close).isCloseTo(1.07800 - 0.00002, within(1e-12));
        assertThat(s.netR).isLessThan(-1.0);
    }

    @Test
    void movedBeforeFillExpiresWithoutAFill() {
        Signal s = longSignal();
        tick(T.plusSeconds(12), 1.07840, 1.07842); // bid already through the stop at fill time
        assertThat(s.state).isEqualTo(Signal.State.EXPIRED);
        assertThat(s.flags).contains("MOVED_BEFORE_FILL");
        assertThat(s.fill).isNaN();
    }

    @Test
    void exitNowFillsAfterTheLatencyWithSlippage() {
        Signal s = longSignal();
        tick(T.plusSeconds(10), 1.07999, 1.08001);
        Instant req = T.plusSeconds(900);
        tracker.requestExit(s, req, Signal.CloseReason.TIME_STOP, "time stop");
        tick(req.plusSeconds(3), 1.08050, 1.08052);
        assertThat(s.state).isEqualTo(Signal.State.ACTIVE);
        tick(req.plusSeconds(11), 1.08060, 1.08062);
        assertThat(s.closeReason).isEqualTo(Signal.CloseReason.TIME_STOP);
        assertThat(s.close).isCloseTo(1.08060 - 0.00002, within(1e-12));
    }

    @Test
    void stopOnlyTightensAndATrailedStopExitIsAnExitRule() {
        Signal s = longSignal();
        tick(T.plusSeconds(10), 1.07999, 1.08001);
        tracker.moveStop(s, 1.0790);
        tracker.moveStop(s, 1.0788);
        assertThat(s.stop).isEqualTo(1.0790);
        tick(T.plusSeconds(100), 1.07895, 1.07897);
        assertThat(s.closeReason).isEqualTo(Signal.CloseReason.EXIT_RULE);
    }

    @Test
    void swapIsChargedPerRolloverAndTripledOnWednesday() {
        Signal s = longSignal();
        tick(T.plusSeconds(10), 1.07999, 1.08001);
        tick(Instant.parse("2024-03-07T10:00:00Z"), 1.08100, 1.08102); // crossed Wed 17:00 NY
        assertThat(s.nights).isEqualTo(3);
        assertThat(s.swapPrice).isCloseTo(3 * -0.71 * 0.0001, within(1e-12));
        tick(Instant.parse("2024-03-08T10:00:00Z"), 1.08100, 1.08102); // + Thursday
        assertThat(s.nights).isEqualTo(4);
    }

    @Test
    void shortIsTheMirror() {
        Signal s = signal(Direction.SHORT, 1.08000, 1.08002, 1.0815, 1.0770);
        tick(T.plusSeconds(10), 1.07999, 1.08001);
        assertThat(s.fill).isCloseTo(1.07999 - 0.00002, within(1e-12));
        tick(T.plusSeconds(200), 1.07690, 1.07692);
        assertThat(s.closeReason).isEqualTo(Signal.CloseReason.TARGET);
        assertThat(s.netR).isGreaterThan(1.9);
    }
}
