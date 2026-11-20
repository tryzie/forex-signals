package dev.forexsignals.strategy.impl;

import dev.forexsignals.strategy.api.StrategyDef;

/** Pre-registered hypothesis: EURUSD Donchian breakout with the cross-market confirmation gate. */
@StrategyDef
public class EurusdDonchianConf extends Confirmed {
    public EurusdDonchianConf() { super("eurusd_donchian_conf", new EurusdDonchianBase()); }
}
