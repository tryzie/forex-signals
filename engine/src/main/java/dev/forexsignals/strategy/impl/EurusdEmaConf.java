package dev.forexsignals.strategy.impl;

import dev.forexsignals.strategy.api.StrategyDef;

/** Pre-registered hypothesis: EURUSD EMA pullback with the cross-market confirmation gate. */
@StrategyDef
public class EurusdEmaConf extends Confirmed {
    public EurusdEmaConf() { super("eurusd_ema_conf", new EurusdEmaBase()); }
}
