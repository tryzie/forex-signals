package dev.forexsignals.strategy.impl;

import dev.forexsignals.strategy.api.StrategyDef;

/** H1-entry variant of the RSI mean reversion (H4 ADX regime). Run only if the M15 version fails on costs. */
@StrategyDef
public class RsiMeanReversionH1 extends RsiMeanReversion {
    public RsiMeanReversionH1() { super(Timeframes.H1); }
}
