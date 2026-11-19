package dev.forexsignals.feed;

import dev.forexsignals.config.ConfigException;
import dev.forexsignals.market.SymbolCatalog.FeedName;
import dev.forexsignals.market.SymbolCatalog.SymbolDef;
import dev.forexsignals.store.MarketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Startup symbol check (PLAN §2):
 *  - a REQUIRED symbol missing on the feed stops startup with a clear message;
 *  - an optional one is logged as a warning (feed_event SYMBOL_MISSING) and skipped;
 *  - every spec is compared with the last stored one; a change writes a new symbol_spec row and a
 *    SPEC_CHANGED event, because changed specs silently corrupt position sizing.
 * Returns the symbols that are available on this feed.
 */
public final class SymbolVerifier {

    private static final Logger log = LoggerFactory.getLogger(SymbolVerifier.class);

    private SymbolVerifier() {}

    public static List<SymbolDef> verify(String feed, List<SymbolDef> wanted, Map<String, SymbolSpec> specs, MarketStore store, Instant now) {
        List<String> missingRequired = new ArrayList<>();
        List<SymbolDef> available = new ArrayList<>();
        for (SymbolDef d : wanted) {
            FeedName fn = d.on(feed).orElseThrow();
            SymbolSpec spec = specs.get(d.code());
            if (store != null) store.upsertSymbol(d);
            if (spec == null) {
                if (fn.required()) missingRequired.add(d.code() + " (" + fn.brokerSymbol() + ")");
                else {
                    log.warn("feed {}: optional symbol {} ({}) not found; continuing without it", feed, d.code(), fn.brokerSymbol());
                    if (store != null) {
                        store.feedEvent(now, feed, d.code(), "SYMBOL_MISSING", Map.of("brokerSymbol", fn.brokerSymbol(), "required", false));
                        store.upsertSymbolFeed(feed, d.code(), fn.brokerSymbol(), null, false, false, now);
                    }
                }
                continue;
            }
            available.add(d);
            if (store == null) continue;
            store.upsertSymbolFeed(feed, d.code(), spec.brokerSymbol(), spec.brokerSymbolId(), fn.required(), true, now);
            String hash = spec.hash();
            var previous = store.latestSpecHash(feed, d.code());
            if (previous.isEmpty()) {
                store.insertSpec(spec, now);
                log.info("feed {}: {} spec stored (digits={}, contract={}, minVol={}, step={})", feed, d.code(), spec.digits(),
                        spec.contractSize(), spec.minVolume(), spec.volumeStep());
            } else if (!previous.get().equals(hash)) {
                store.insertSpec(spec, now);
                store.feedEvent(now, feed, d.code(), "SPEC_CHANGED", Map.of("previousHash", previous.get(), "newHash", hash));
                log.warn("feed {}: {} SPEC CHANGED since last start; new spec row stored. Check sizing inputs.", feed, d.code());
            }
            if (spec.digits() != null && spec.digits() != d.digits()) {
                log.warn("feed {}: {} reports {} digits, symbols.xml says {}; the feed's value is used", feed, d.code(), spec.digits(), d.digits());
            }
        }
        if (!missingRequired.isEmpty()) {
            throw new ConfigException("feed " + feed + ": required symbol(s) missing: " + String.join(", ", missingRequired)
                    + ". Check the broker symbol names in config/symbols.xml.");
        }
        return available;
    }
}
