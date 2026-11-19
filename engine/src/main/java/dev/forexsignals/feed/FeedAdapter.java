package dev.forexsignals.feed;

import dev.forexsignals.market.PriceBasis;
import dev.forexsignals.market.SymbolCatalog.SymbolDef;

import java.util.List;
import java.util.Map;

/**
 * A market-data source (cTrader, Deriv, synthetic). Read-only by construction: the interface has no way
 * to send anything but market-data and account-info requests (CLAUDE.md rule 1).
 *
 * Lifecycle: {@link #loadSymbols} (startup symbol check), then {@link #start} (streams ticks to the
 * listener until {@link #close}). Adapters reconnect by themselves with exponential backoff and jitter
 * (1 s to 60 s), reporting each CONNECTED / DISCONNECTED transition to the listener.
 */
public interface FeedAdapter extends AutoCloseable {

    /** Feed name stored with every row ("ctrader", "deriv", "synthetic"). Feeds are never pooled. */
    String name();

    /** The price candles from this feed are built on (cTrader: bid; Deriv: its quote). */
    default PriceBasis priceBasis() { return PriceBasis.BID; }

    /** Looks up the wanted symbols on the feed. Missing symbols are simply absent from the result. */
    Map<String, SymbolSpec> loadSymbols(List<SymbolDef> wanted) throws Exception;

    /** Starts streaming ticks for these symbols (non-blocking). */
    void start(List<SymbolDef> symbols, Map<String, SymbolSpec> specs, FeedListener listener) throws Exception;

    @Override
    void close();
}
