package dev.forexsignals.market;

import java.time.Instant;

/**
 * One quote from a feed.
 *  bid, ask   NaN when the feed does not provide them (e.g. Deriv history is quote-only).
 *  quote      the feed's own reference price when it publishes one (Deriv: the mid "quote"); NaN otherwise.
 *  ts         the feed's timestamp; recvTs is when we received it; seq is the arrival order within one
 *             process run (used only to break ties deterministically).
 */
public record Tick(String feed, String symbol, Instant ts, double bid, double ask, double quote, Instant recvTs, long seq) {

    /** Convenience for feeds without a separate quote (cTrader, synthetic). */
    public Tick(String feed, String symbol, Instant ts, double bid, double ask, Instant recvTs, long seq) {
        this(feed, symbol, ts, bid, ask, Double.NaN, recvTs, seq);
    }

    public boolean hasBid() { return !Double.isNaN(bid); }

    public boolean hasAsk() { return !Double.isNaN(ask); }

    public double spread() { return hasBid() && hasAsk() ? ask - bid : Double.NaN; }

    /** The price candles are built from, for a feed's price basis. */
    public double price(PriceBasis basis) {
        return switch (basis) {
            case BID -> bid;
            case QUOTE -> !Double.isNaN(quote) ? quote : (hasBid() && hasAsk() ? (bid + ask) / 2.0 : bid);
        };
    }
}
