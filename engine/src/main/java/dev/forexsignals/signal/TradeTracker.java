package dev.forexsignals.signal;

import dev.forexsignals.market.Candle;
import dev.forexsignals.market.Tick;
import dev.forexsignals.paper.CostModel;
import dev.forexsignals.risk.NewsCalendar;
import dev.forexsignals.risk.RiskApproved;
import dev.forexsignals.risk.RiskGate;
import dev.forexsignals.strategy.api.Direction;
import dev.forexsignals.strategy.api.SignalProposal;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Tick-level signal lifecycle (PLAN §7.2, §3), identical for backtest, replay and live:
 *  PENDING  waits for the first tick at or after alert + latency. If by then the bid (long) / ask (short) is already at
 *           or beyond the stop or target, the signal EXPIRES (MOVED_BEFORE_FILL); otherwise it fills at the ask (long) or
 *           bid (short) plus slippage, against us.
 *  ACTIVE   stop: long when bid <= stop, filled at min(stop, bid) - slippage (a gap fills at the worse price; short
 *           mirrored with the ask). Target: filled exactly at the target, no positive slippage. Stop is checked first.
 *           Exit-now requests (strategy exit rules, invalidation, time stop, kill switch) fill at the first tick at or
 *           after request + latency, minus slippage.
 *           Swap is charged for every 17:00 New York rollover held (triple on the Wednesday rollover).
 *  Results  gross R = price move mid-to-mid from the alert to the exit tick, over R (no costs, no latency fill);
 *           net R = (exit - fill) over R, minus round-trip commission, plus swap. pnlUsd uses the lots from the gate.
 * Backtest-only extras via {@link M1Bars}: the same-bar ambiguity flag (stop and target inside the same M1 bar) and the
 * worst-case-within-the-minute fill reported next to the model fill.
 */
public final class TradeTracker {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    /** Optional M1 bar lookup (backtest only). */
    public interface M1Bars {
        Candle at(String symbol, Instant t);
    }

    private final CostModel costs;
    private final NewsCalendar news;
    private final Duration latency;
    private final M1Bars m1;
    private final boolean syntheticIntraBar;
    private final List<Signal> all = new ArrayList<>();
    private final List<Signal> open = new ArrayList<>();

    public TradeTracker(CostModel costs, NewsCalendar news, Duration latency, M1Bars m1) { this(costs, news, latency, m1, false); }

    public TradeTracker(CostModel costs, NewsCalendar news, Duration latency, M1Bars m1, boolean syntheticIntraBar) {
        this.syntheticIntraBar = syntheticIntraBar;
        this.costs = costs;
        this.news = news;
        this.latency = latency;
        this.m1 = m1;
    }

    public List<Signal> all() { return all; }

    public List<Signal> open() { return open; }

    /** The only way in: a signal the risk gate approved. */
    public Signal register(RiskApproved<RiskGate.Sized> approved, String strategyName, String version, String paramsHash, SignalProposal p,
                           double stop, double target) {
        RiskGate.Sized z = approved.value();
        RiskGate.Candidate c = z.candidate();
        Signal s = new Signal(c.strategyId(), strategyName, version, paramsHash, c.symbol().code(), c.direction(), c.at(), c.at().plus(latency),
                c.bid(), c.ask(), stop, target, z.lots(), z.riskUsd(), z.estCostR(), p.timeStopBars(), p.reasons(), List.of(),
                p.exitPlan(), p.invalidation(), p.memo(), approved.deliver());
        s.events.add(c.at() + " PENDING ref=" + s.reference + " stop=" + stop + " target=" + target);
        all.add(s);
        open.add(s);
        return s;
    }

    private boolean inNews(Signal s, Instant t) {
        if (news == null) return false;
        var spec = costs.spec(s.symbol);
        return news.status(t, NewsCalendar.currencies(spec.base(), spec.quote())) != NewsCalendar.Status.CLEAR;
    }

    public void onTick(Tick t) {
        for (int i = 0; i < open.size(); i++) {
            Signal s = open.get(i);
            if (!s.symbol.equals(t.symbol())) continue;
            if (s.state == Signal.State.PENDING) pending(s, t);
            else if (s.state == Signal.State.ACTIVE) active(s, t);
        }
        open.removeIf(s -> !s.open());
    }

    private void pending(Signal s, Tick t) {
        if (t.ts().isBefore(s.fillAfter)) return;
        int d = s.direction.sign();
        double mark = d > 0 ? t.bid() : t.ask();
        if (d * (mark - s.stop) <= 0 || d * (mark - s.target) >= 0) {
            s.state = Signal.State.EXPIRED;
            s.closeReason = Signal.CloseReason.EXPIRED;
            s.closedAt = t.ts();
            s.flags.add("MOVED_BEFORE_FILL");
            s.events.add(t.ts() + " EXPIRED moved before fill (mark " + mark + ")");
            return;
        }
        double slip = costs.slippage(s.symbol, t.ts(), inNews(s, t.ts()));
        s.fill = d > 0 ? t.ask() + slip : t.bid() - slip;
        s.entrySpread = t.ask() - t.bid();
        s.entrySlip = slip;
        s.filledAt = t.ts();
        s.state = Signal.State.ACTIVE;
        s.best = mark;
        s.commissionUsd = 2 * costs.commissionUsdPerSidePerLot(s.symbol) * s.lots;
        s.events.add(t.ts() + " ACTIVE fill " + s.fill);
        if (m1 != null) {
            // Worst case within the fill minute: the adverse extreme of the M1 bar the fill happened in (+ spread for longs).
            Candle b = m1.at(s.symbol, t.ts());
            if (b != null) {
                double worst = d > 0 ? b.high() + costs.modelledSpread(s.symbol, b.openTime()) + slip : b.low() - slip;
                s.memoWorst = worst;
            }
        }
    }

    private void active(Signal s, Tick t) {
        int d = s.direction.sign();
        double mark = d > 0 ? t.bid() : t.ask();
        // Swap: one per 17:00 New York rollover crossed (triple when the rollover ends a Wednesday trading day).
        LocalDate dayNow = tradingDay(t.ts()), dayLast = tradingDay(s.lastMark == null ? s.filledAt : s.lastMark);
        while (dayLast.isBefore(dayNow)) {
            // A rollover happens at 17:00 New York on Mon-Fri only; Wednesday's counts 3 nights (it covers the weekend).
            DayOfWeek dow = dayLast.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                int mult = dow == DayOfWeek.WEDNESDAY ? 3 : 1;
                s.swapPrice += mult * costs.swapPerNight(s.symbol, d > 0);
                s.nights += mult;
            }
            dayLast = dayLast.plusDays(1);
        }
        s.lastMark = t.ts();
        if (d * (mark - s.best) > 0) s.best = mark;
        double exc = d * (mark - s.reference) / s.rUnit;
        s.mfeR = Math.max(s.mfeR, exc);
        s.maeR = Math.min(s.maeR, exc);

        if (d * (mark - s.stop) <= 0) {
            double slip = costs.slippage(s.symbol, t.ts(), inNews(s, t.ts()));
            // A real tick (or a synthetic bar's OPEN tick) that jumps through the stop fills at its own, worse price.
            // Inside a synthetic M1 path (ms 1..3) the price passed through the stop: fill at the stop.
            boolean intraBar = syntheticIntraBar && t.ts().toEpochMilli() % 60_000L != 0;
            double level = intraBar ? s.stop : d > 0 ? Math.min(s.stop, t.bid()) : Math.max(s.stop, t.ask());
            flagAmbiguity(s, t);
            s.exitSlip = slip;
            close(s, t, level, level - d * slip, s.stop == s.initialStop ? Signal.CloseReason.STOP : Signal.CloseReason.EXIT_RULE);
            return;
        }
        if (d * (mark - s.target) >= 0) {
            flagAmbiguity(s, t);
            close(s, t, s.target, s.target, Signal.CloseReason.TARGET);
            return;
        }
        if (s.exitRequestedAt != null && !t.ts().isBefore(s.exitRequestedAt.plus(latency))) {
            double slip = costs.slippage(s.symbol, t.ts(), inNews(s, t.ts()));
            s.exitSlip = slip;
            close(s, t, mark, mark - d * slip, s.exitRequestReason);
        }
    }

    /** Backtest only: both stop and target inside the M1 bar of the exit (whichever was hit), so the order is assumed. */
    private void flagAmbiguity(Signal s, Tick t) {
        if (m1 == null) return;
        Candle b = m1.at(s.symbol, t.ts());
        if (b == null) return;
        double spread = costs.modelledSpread(s.symbol, b.openTime());
        boolean longSide = s.direction == Direction.LONG;
        // Long exits on the bid (bar values); short exits on the ask (bar + spread).
        double hi = longSide ? b.high() : b.high() + spread, lo = longSide ? b.low() : b.low() + spread;
        boolean bothInside = longSide ? (hi >= s.target && lo <= s.stop) : (lo <= s.target && hi >= s.stop);
        if (bothInside) s.flags.add("AMBIGUOUS_BAR");
    }

    /** An "exit now" alert at time at (strategy exit rule, invalidation, time stop, kill switch). */
    public void requestExit(Signal s, Instant at, Signal.CloseReason reason, String rule) {
        if (s.state == Signal.State.PENDING) {
            s.state = reason == Signal.CloseReason.INVALIDATED ? Signal.State.INVALIDATED : Signal.State.EXPIRED;
            s.closeReason = reason;
            s.closedAt = at;
            s.events.add(at + " cancelled before fill: " + rule);
            open.remove(s);
            return;
        }
        if (s.state != Signal.State.ACTIVE || s.exitRequestedAt != null) return;
        s.exitRequestedAt = at;
        s.exitRequestReason = reason;
        s.exitRequestRule = rule;
        s.events.add(at + " EXIT NOW requested: " + rule);
    }

    /** Moves the stop; only ever tightens. */
    public void moveStop(Signal s, double level) {
        if (s.state != Signal.State.ACTIVE || Double.isNaN(level)) return;
        int d = s.direction.sign();
        if (d * (level - s.stop) > 0) {
            s.stop = level;
            s.events.add("stop -> " + level);
        }
    }

    /** End of data: close what is still open at its symbol's last mark (flagged; excluded from the statistics). */
    public void closeAllAtEnd(java.util.function.Function<String, Tick> lastTick, Instant at) {
        for (Signal s : List.copyOf(open)) {
            Tick last = lastTick.apply(s.symbol);
            if (s.state == Signal.State.ACTIVE && last != null) {
                int d = s.direction.sign();
                double m = d > 0 ? last.bid() : last.ask();
                close(s, last, m, m, Signal.CloseReason.END_OF_DATA);
            } else {
                s.state = Signal.State.EXPIRED;
                s.closeReason = Signal.CloseReason.END_OF_DATA;
                s.closedAt = at;
            }
            s.flags.add("END_OF_DATA");
        }
        open.clear();
    }

    /**
     * level = the exit price before slippage on the closing side (bid for longs, ask for shorts): the target, the stop
     * (or the gapped price), or the market at an exit-now. Gross R = level vs the alert price on the SAME side, so it
     * excludes spread, slippage, commission and swap but includes the price move (and the latency drift).
     */
    private void close(Signal s, Tick t, double level, double price, Signal.CloseReason reason) {
        int d = s.direction.sign();
        s.close = price;
        s.closedAt = t.ts();
        s.closeReason = reason;
        s.state = Signal.State.CLOSED;
        s.midAtExit = (t.bid() + t.ask()) / 2;
        double sameSideRef = d > 0 ? s.bidAtSignal : s.askAtSignal;
        s.grossR = d * (level - sameSideRef) / s.rUnit;
        double commissionPrice = 2 * costs.commissionPerSideInPrice(s.symbol, s.fill);
        s.netR = (d * (price - s.fill) - commissionPrice + s.swapPrice) / s.rUnit;
        var spec = costs.spec(s.symbol);
        s.pnlUsd = s.lots * spec.contractSize() * (d * (price - s.fill) + s.swapPrice) * costs.quoteToUsd(s.symbol, price) - s.commissionUsd;
        if (!Double.isNaN(s.memoWorst)) s.worstCaseNetR = s.netR - d * (s.memoWorst - s.fill) / s.rUnit;
        s.events.add(t.ts() + " CLOSED " + reason + " at " + price + " net " + s.netR + "R");
    }

    /** Floating P/L in USD of the open trades at the given marks (for equity, daily cap and kill switch). */
    public double floatingUsd(java.util.function.Function<String, Tick> lastTick) {
        double sum = 0;
        for (Signal s : open) {
            if (s.state != Signal.State.ACTIVE) continue;
            Tick t = lastTick.apply(s.symbol);
            if (t == null) continue;
            int d = s.direction.sign();
            double mark = d > 0 ? t.bid() : t.ask();
            sum += s.lots * costs.spec(s.symbol).contractSize() * (d * (mark - s.fill) + s.swapPrice) * costs.quoteToUsd(s.symbol, mark)
                    - s.commissionUsd;
        }
        return sum;
    }

    /** The 17:00 New York trading day a time belongs to (the day after 17:00 rolls over). */
    public static LocalDate tradingDay(Instant t) {
        var ny = t.atZone(NY);
        return ny.getHour() >= 17 ? ny.toLocalDate().plusDays(1) : ny.toLocalDate();
    }

    static Direction dir(int sign) { return sign > 0 ? Direction.LONG : Direction.SHORT; }
}
