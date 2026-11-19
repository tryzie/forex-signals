package dev.forexsignals.risk;

/** The risk gate's checks, in evaluation order (PLAN §9). */
public enum RejectionReason {
    NO_STOP, RR_TOO_LOW, COST_VS_STOP, SPREAD_CAP, OUTSIDE_SESSION, NEWS_BLACKOUT, NEWS_BLACKOUT_FALLBACK, DAILY_LOSS_CAP,
    KILL_SWITCH, MAX_OPEN, CURRENCY_EXPOSURE, USD_NET_EXPOSURE, FEED_UNHEALTHY, CONTEXT_ONLY_SYMBOL, SIZE_BELOW_MIN,
    /** Not a rejection: the signal is journaled and tracked but not delivered (SHADOW mode). */
    STRATEGY_NOT_LIVE
}
