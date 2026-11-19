package dev.forexsignals.market;

/**
 * Which price a feed's candles are built from. Fixed per feed and recorded in the docs; feeds are never pooled.
 *  BID    cTrader (matches cTrader trendbars, which are bid) and the synthetic feed.
 *  QUOTE  Deriv: its history API (ticks and candles) only provides its mid "quote", so live Deriv candles
 *         are built on the same quote, otherwise they could never be checked against Deriv's own history.
 */
public enum PriceBasis { BID, QUOTE }
