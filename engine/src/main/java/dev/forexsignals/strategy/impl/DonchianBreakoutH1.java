package dev.forexsignals.strategy.impl;

import dev.forexsignals.strategy.api.StrategyDef;

/** H1-entry variant of the Donchian breakout (D1 trend context). Run only if the M15 version fails on costs. */
@StrategyDef
public class DonchianBreakoutH1 extends DonchianBreakout {
    public DonchianBreakoutH1() { super(Timeframes.H1); }
}
