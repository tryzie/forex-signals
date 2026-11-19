package dev.forexsignals.paper;

/**
 * What costs and sizing need about a symbol: contract size (units per lot), volume rules, pip size and swap in pips per
 * lot per night (cTrader swapCalculationType PIPS). Loaded from the latest stored cTrader spec.
 */
public record InstrumentSpec(String symbol, String base, String quote, double contractSize, double minVolume, double volumeStep,
                             double pipSize, double swapLongPips, double swapShortPips) {}
