package dev.forexsignals.backtest;

import dev.forexsignals.history.SpreadSampler;
import dev.forexsignals.market.Tick;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.market.TradingHours;
import dev.forexsignals.paper.SpreadModel;
import dev.forexsignals.store.MarketStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * M1 fill-model bias (PLAN §14.4): at every M15 close T inside the sampled tick weeks, compare
 *   the REAL fill  = first recorded tick at or after T + latency (ask for a long, bid for a short), with
 *   the MODEL fill = open of the first M1 bar at or after T + latency (+ the modelled spread for a long).
 * Adverse bias: long = real ask - model ask; short = model bid - real bid (positive = real fills are worse).
 * The price drift between the two moments cancels between long and short; what remains is mainly the spread-model error.
 * If the mean adverse bias of a symbol is positive it is added as extra slippage for that symbol (and reported).
 */
public final class FillBias {

    public record SymbolBias(String symbol, int samples, double longAdverse, double shortAdverse, double meanAdverse, double extraSlippage) {}

    private FillBias() {}

    public static Map<String, SymbolBias> measure(MarketStore store, String feed, Map<String, M1Series> m1, SpreadModel model,
                                                  List<SpreadSampler.Week> weeks, List<String> symbols, Duration latency) {
        Map<String, SymbolBias> out = new TreeMap<>();
        for (String sym : symbols) {
            M1Series bars = m1.get(sym);
            double sumL = 0, sumS = 0;
            int n = 0;
            for (SpreadSampler.Week w : weeks) {
                List<Tick> ticks = new ArrayList<>();
                store.streamTicks(feed, List.of(sym), w.from(), w.to(), ticks::add);
                if (ticks.isEmpty() || bars == null) continue;
                int j = 0;
                for (Instant t = Timeframe.M15.bucketStart(w.from()); t.isBefore(w.to()); t = t.plus(Duration.ofMinutes(15))) {
                    if (!TradingHours.FX.isOpen(t)) continue;
                    Instant at = t.plus(latency);
                    while (j < ticks.size() && ticks.get(j).ts().isBefore(at)) j++;
                    if (j >= ticks.size()) break;
                    Tick real = ticks.get(j);
                    if (!real.hasAsk() || Duration.between(at, real.ts()).toSeconds() > 60) continue;
                    var bar = bars.at(at.plusSeconds(60 - at.getEpochSecond() % 60)); // first M1 bar opening at/after T + latency
                    if (bar == null) continue;
                    double modelBid = bar.open(), modelAsk = bar.open() + model.median(sym, bar.openTime());
                    sumL += real.ask() - modelAsk;
                    sumS += modelBid - real.bid();
                    n++;
                }
            }
            double l = n == 0 ? Double.NaN : sumL / n, s = n == 0 ? Double.NaN : sumS / n, mean = n == 0 ? Double.NaN : (l + s) / 2;
            out.put(sym, new SymbolBias(sym, n, l, s, mean, n > 0 && mean > 0 ? mean : 0));
        }
        return out;
    }
}
