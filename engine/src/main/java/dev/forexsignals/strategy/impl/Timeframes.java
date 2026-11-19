package dev.forexsignals.strategy.impl;

import dev.forexsignals.market.Timeframe;

/**
 * Entry and context timeframes of a baseline. M15 is the approved default; the H1 variant (same rules one timeframe up,
 * H4 and D1 as context) exists for baselines that fail on costs at M15 (CLAUDE.md). "fast" is the timeframe of the
 * slope/regime filter (H1 for M15 entries, H4 for H1 entries); "slow" is the trend timeframe (H4, resp. D1).
 */
public record Timeframes(Timeframe entry, Timeframe fast, Timeframe slow, String suffix) {
    public static final Timeframes M15 = new Timeframes(Timeframe.M15, Timeframe.H1, Timeframe.H4, "");
    public static final Timeframes H1 = new Timeframes(Timeframe.H1, Timeframe.H4, Timeframe.D1, "_h1");
}
