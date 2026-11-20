package dev.forexsignals.strategy.impl;

import dev.forexsignals.strategy.api.StrategyDef;

import java.util.List;

/** Pre-registered hypothesis (docs/hyp/eurusd-confirmation.md): the Phase 2 EMA pullback on EURUSD only, no confirmation. */
@StrategyDef
public class EurusdEmaBase extends EmaPullback {
    public EurusdEmaBase() { super(Timeframes.M15, "eurusd_ema_base", List.of("EURUSD")); }
}
