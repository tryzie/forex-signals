package dev.forexsignals.strategy.api;

import dev.forexsignals.market.Timeframe;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * name, version, source ("baseline" or a video URL), symbols, entry timeframe, context timeframes, parameters and the
 * indicators the strategy reads. Parameters are the same for every symbol (no per-symbol tuning).
 * contextSymbols / contextIndicators: OTHER symbols the strategy may read as context (never traded), and the indicators
 * computed for each of them. Context is read only through the guarded context API (closed bars only).
 */
public record StrategyMeta(String name, String version, String source, List<String> symbols, Timeframe entryTf,
                           List<Timeframe> contextTfs, Map<String, Object> params, List<IndicatorRequest> indicators,
                           List<String> contextSymbols, List<IndicatorRequest> contextIndicators) {
    public StrategyMeta {
        symbols = List.copyOf(symbols);
        contextTfs = List.copyOf(contextTfs);
        params = java.util.Collections.unmodifiableMap(new TreeMap<>(params));
        indicators = List.copyOf(indicators);
        contextSymbols = List.copyOf(contextSymbols);
        contextIndicators = List.copyOf(contextIndicators);
    }

    public StrategyMeta(String name, String version, String source, List<String> symbols, Timeframe entryTf,
                        List<Timeframe> contextTfs, Map<String, Object> params, List<IndicatorRequest> indicators) {
        this(name, version, source, symbols, entryTf, contextTfs, params, indicators, List.of(), List.of());
    }

    public String id() { return name + "@" + version; }
}
