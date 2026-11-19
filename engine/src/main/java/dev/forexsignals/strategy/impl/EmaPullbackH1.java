package dev.forexsignals.strategy.impl;

import dev.forexsignals.strategy.api.StrategyDef;

/** H1-entry variant of the EMA pullback (H4 slope, D1 trend). Run only if the M15 version fails on costs. */
@StrategyDef
public class EmaPullbackH1 extends EmaPullback {
    public EmaPullbackH1() { super(Timeframes.H1); }
}
