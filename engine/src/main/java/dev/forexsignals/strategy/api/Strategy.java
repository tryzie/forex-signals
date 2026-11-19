package dev.forexsignals.strategy.api;

import java.util.Optional;

/**
 * A signal strategy (PLAN §7.1). Called ONLY when a candle of meta().entryTf() closes for one of meta().symbols(), after
 * the warm-up, with a context that exposes closed bars only.
 */
public interface Strategy {
    StrategyMeta meta();

    Optional<SignalProposal> onCandleClose(StrategyContext ctx);

    /** Called at each entry-timeframe close for every ACTIVE trade of this strategy and symbol. */
    Optional<ExitDecision> onOpenSignalCandleClose(StrategyContext ctx, OpenTradeView open);
}
