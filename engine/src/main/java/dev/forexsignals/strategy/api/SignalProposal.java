package dev.forexsignals.strategy.api;

import java.util.List;
import java.util.Map;

/**
 * What a strategy proposes at a candle close. It contains NO size: only the risk gate sizes a signal.
 * Entry is always "market after the manual-entry latency" (PLAN §7.3); stop and target are fixed price levels the
 * engine derives from the alert-time reference price. memo carries values the strategy needs later for its exits
 * (e.g. the broken Donchian level), handed back in {@link OpenTradeView#memo()}.
 */
public record SignalProposal(Direction direction, StopRule stop, double targetR, int timeStopBars, List<Reason> reasons,
                             String exitPlan, String invalidation, Map<String, Double> memo) {
    public SignalProposal {
        reasons = List.copyOf(reasons);
        memo = Map.copyOf(memo);
    }
}
