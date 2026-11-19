package dev.forexsignals.history;

import dev.forexsignals.feed.SyntheticFeed;
import dev.forexsignals.market.Candle;
import dev.forexsignals.market.CandleBuilder;
import dev.forexsignals.market.PriceBasis;
import dev.forexsignals.market.SymbolCatalog.SymbolDef;
import dev.forexsignals.market.Tick;
import dev.forexsignals.market.Timeframe;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * A history source backed by synthetic ticks (no network). M1 bars are built from those ticks once.
 * Failure injection: {@code rateLimitOnCall} decides, per 1-based call number, whether the call answers
 * "rate limited"; {@code onCall} runs before each call (used to simulate a kill mid-import).
 */
final class FakeHistorySource implements HistorySource {

    private final Map<String, List<Tick>> ticks = new HashMap<>();
    private final Map<String, List<Candle>> m1 = new HashMap<>();
    IntPredicate rateLimitOnCall = n -> false;
    Runnable onCall = () -> {};
    int calls;
    final List<Instant[]> requestedRanges = new ArrayList<>();
    Duration span = Duration.ofDays(3);

    private final String feedName;

    FakeHistorySource(List<SymbolDef> symbols, Instant from, Instant to, long seed) {
        this("synthetic", symbols, from, to, seed);
    }

    FakeHistorySource(String feedName, List<SymbolDef> symbols, Instant from, Instant to, long seed) {
        this.feedName = feedName;
        SyntheticFeed feed = new SyntheticFeed(seed);
        for (SymbolDef d : symbols) {
            List<Tick> t = feed.generate(List.of(d), from, to);
            ticks.put(d.code(), t);
            List<Candle> bars = new ArrayList<>();
            CandleBuilder b = new CandleBuilder(feedName, PriceBasis.BID, Duration.ZERO, c -> {
                if (c.timeframe() == Timeframe.M1) bars.add(new Candle(c.feed(), c.symbol(), c.timeframe(), c.openTime(), c.closeTime(),
                        c.open(), c.high(), c.low(), c.close(), c.tickCount(), Double.NaN, Double.NaN, Double.NaN, Candle.Source.IMPORT));
            });
            t.forEach(b::onTick);
            b.flush();
            m1.put(d.code(), bars);
        }
    }

    List<Candle> allM1(String symbol) { return m1.get(symbol); }

    @Override public String feed() { return feedName; }

    @Override public PriceBasis priceBasis() { return PriceBasis.BID; }

    @Override public Duration maxSpan(Timeframe tf) { return span; }

    private Pacer pacer;

    @Override public void usePacer(Pacer pacer) { this.pacer = pacer; }

    @Override
    public List<Candle> fetchM1(SymbolDef symbol, Instant from, Instant to) throws Exception {
        if (pacer != null) pacer.acquire(); // like real sources: every network request is paced
        calls++;
        onCall.run();
        if (rateLimitOnCall.test(calls)) throw new RateLimitedException("fake: too many requests");
        requestedRanges.add(new Instant[]{from, to});
        return m1.get(symbol.code()).stream().filter(c -> !c.openTime().isBefore(from) && c.openTime().isBefore(to)).toList();
    }

    @Override
    public List<Candle> fetchCandles(SymbolDef symbol, Timeframe tf, Instant from, Instant to) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Tick> fetchTicks(SymbolDef symbol, Instant from, Instant to) {
        return ticks.get(symbol.code()).stream().filter(t -> !t.ts().isBefore(from) && t.ts().isBefore(to)).toList();
    }
}
