package dev.forexsignals.strategy.impl;

import dev.forexsignals.strategy.api.Direction;
import dev.forexsignals.strategy.api.Reason;
import dev.forexsignals.strategy.api.SignalProposal;
import dev.forexsignals.strategy.api.Strategy;
import dev.forexsignals.strategy.api.StrategyContext;

import java.util.List;

/** A baseline whose exits a random-entry control can borrow: the parent's stop, target, trailing, time stop and invalidation. */
interface Baseline extends Strategy {
    SignalProposal randomEntryPlan(StrategyContext ctx, Direction d, List<Reason> why);
}
