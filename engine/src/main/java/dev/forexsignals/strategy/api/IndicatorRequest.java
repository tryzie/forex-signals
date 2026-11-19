package dev.forexsignals.strategy.api;

import dev.forexsignals.market.Timeframe;

/** An indicator a strategy declares in its meta. Only declared indicators can be read (and they drive the warm-up). */
public record IndicatorRequest(Timeframe tf, IndicatorKind kind, int period) {}
