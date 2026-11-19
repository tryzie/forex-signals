package dev.forexsignals.signal;

import dev.forexsignals.risk.RiskGate;
import dev.forexsignals.strategy.api.Direction;
import dev.forexsignals.strategy.api.Reason;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One signal and, once filled, its trade (PLAN §7.2). Mutable; owned by the {@link TradeTracker} on the engine thread.
 * Prices: reference = ask (long) / bid (short) at the alert; stop/target are fixed levels derived from it; R = |reference - initial stop|.
 */
public final class Signal {

    public enum State { PENDING, ACTIVE, CLOSED, EXPIRED, INVALIDATED }

    public enum CloseReason { TARGET, STOP, TIME_STOP, EXIT_RULE, INVALIDATED, EXPIRED, KILL_SWITCH, MANUAL, END_OF_DATA }

    public final UUID id = UUID.randomUUID();
    public final String strategyId, strategyName, version, paramsHash, symbol;
    public final Direction direction;
    public final Instant signalAt, fillAfter;
    public final double reference, bidAtSignal, askAtSignal, initialStop, target, rUnit, lots, riskUsd, estCostR;
    public final int timeStopBars;
    public final List<Reason> reasons;
    public final List<RiskGate.Check> checks;
    public final String exitPlan, invalidation;
    public final Map<String, Double> memo;
    public final boolean delivered;

    public State state = State.PENDING;
    public double stop;
    public Instant filledAt, closedAt, exitRequestedAt;
    public String exitRequestRule;
    public CloseReason exitRequestReason;
    public double fill = Double.NaN, close = Double.NaN, midAtExit = Double.NaN;
    public int barsSinceEntry;
    public double best = Double.NaN, mfeR, maeR;
    public double swapPrice;
    /** Spread at the fill tick and the slippage applied at entry and exit (for the cost-stress re-pricing). */
    public double entrySpread = Double.NaN, entrySlip, exitSlip;
    public Instant lastMark;
    /** Backtest only: the worst-case-within-the-minute entry price (see TradeTracker). */
    public double memoWorst = Double.NaN;
    public int nights;
    public CloseReason closeReason;
    public double grossR = Double.NaN, netR = Double.NaN, pnlUsd = Double.NaN, commissionUsd, worstCaseNetR = Double.NaN;
    public final TreeSet<String> flags = new TreeSet<>();
    public final List<String> events = new ArrayList<>();

    Signal(String strategyId, String strategyName, String version, String paramsHash, String symbol, Direction direction, Instant signalAt,
           Instant fillAfter, double bid, double ask, double stop, double target, double lots, double riskUsd, double estCostR,
           int timeStopBars, List<Reason> reasons, List<RiskGate.Check> checks, String exitPlan, String invalidation,
           Map<String, Double> memo, boolean delivered) {
        this.strategyId = strategyId;
        this.strategyName = strategyName;
        this.version = version;
        this.paramsHash = paramsHash;
        this.symbol = symbol;
        this.direction = direction;
        this.signalAt = signalAt;
        this.fillAfter = fillAfter;
        this.bidAtSignal = bid;
        this.askAtSignal = ask;
        this.reference = direction == Direction.LONG ? ask : bid;
        this.initialStop = stop;
        this.stop = stop;
        this.target = target;
        this.rUnit = Math.abs(reference - stop);
        this.lots = lots;
        this.riskUsd = riskUsd;
        this.estCostR = estCostR;
        this.timeStopBars = timeStopBars;
        this.reasons = List.copyOf(reasons);
        this.checks = List.copyOf(checks);
        this.exitPlan = exitPlan;
        this.invalidation = invalidation;
        this.memo = Map.copyOf(memo);
        this.delivered = delivered;
    }

    public boolean open() { return state == State.PENDING || state == State.ACTIVE; }

    /** A stable key for comparisons across runs (backtest == replay): strategy, symbol, signal time. */
    public String key() { return strategyId + "|" + symbol + "|" + signalAt; }
}
