package dev.forexsignals.strategy.api;

import dev.forexsignals.market.Timeframe;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * name, version, source ("baseline" or a video URL), symbols, entry timeframe, context timeframes, parameters and the
 * indicators the strategy reads. Parameters are the same for every symbol (no per-symbol tuning).
 */
public record StrategyMeta(String name, String version, String source, List<String> symbols, Timeframe entryTf,
                           List<Timeframe> contextTfs, Map<String, Object> params, List<IndicatorRequest> indicators) {
    public StrategyMeta {
        symbols = List.copyOf(symbols);
        contextTfs = List.copyOf(contextTfs);
        params = java.util.Collections.unmodifiableMap(new TreeMap<>(params));
        indicators = List.copyOf(indicators);
    }

    public String id() { return name + "@" + version; }
}
