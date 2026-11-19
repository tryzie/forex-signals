package dev.forexsignals.stats;

import dev.forexsignals.signal.Signal;

import java.time.Instant;
import java.util.List;

/** One closed trade as the statistics see it (R values after costs, plus gross and the worst-case-fill variant). */
public record TradeR(String symbol, Instant entry, Instant exit, double grossR, double netR, double worstNetR, String closeReason) {

    /** Closed trades only; trades cut by the end of the data are excluded (they never finished). */
    public static List<TradeR> of(List<Signal> signals) {
        return signals.stream()
                .filter(s -> s.state == Signal.State.CLOSED && !s.flags.contains("END_OF_DATA"))
                .map(s -> new TradeR(s.symbol, s.filledAt, s.closedAt, s.grossR, s.netR, s.worstCaseNetR, s.closeReason.name()))
                .sorted((a, b) -> a.exit().compareTo(b.exit()))
                .toList();
    }
}
