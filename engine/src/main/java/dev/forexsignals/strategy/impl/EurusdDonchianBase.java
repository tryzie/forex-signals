package dev.forexsignals.strategy.impl;

import dev.forexsignals.strategy.api.StrategyDef;

import java.util.List;

/** Pre-registered hypothesis (docs/hyp/eurusd-confirmation.md): the Phase 2 Donchian breakout on EURUSD only, no confirmation. */
@StrategyDef
public class EurusdDonchianBase extends DonchianBreakout {
    public EurusdDonchianBase() { super(Timeframes.M15, "eurusd_donchian_base", List.of("EURUSD")); }
}
