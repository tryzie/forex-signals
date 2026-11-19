package dev.forexsignals.engine;

import dev.forexsignals.feed.FeedHealth;
import dev.forexsignals.market.Candle;
import dev.forexsignals.market.CandleBuilder;
import dev.forexsignals.market.PriceBasis;
import dev.forexsignals.market.Tick;
import dev.forexsignals.market.TickBuffer;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * The market-data half of the engine, shared by live and replay (CLAUDE.md rule 3: one code path).
 * Per tick: tick buffer, optional recorder (live spread recording), feed health, candle builder.
 * Per clock step: candle closes by grace time, stale checks.
 *
 * Live calls {@link #onTick} / {@link #onTime} from the single engine loop thread; replay calls them
 * synchronously in timestamp order with a simulated clock. Strategies hook in at candle close in Phase 2.
 */
public final class MarketPipeline {

    private final TickBuffer buffer = new TickBuffer(2048);
    private final CandleBuilder builder;
    private final Consumer<Tick> recorder;   // null in replay (ticks come from the recording)
    private final FeedHealth health;         // null when health is not tracked (replay)
    private long ticks;

    public MarketPipeline(String feed, PriceBasis basis, Duration grace, Consumer<Candle> candleSink, LateTickSink lateSink,
                          Consumer<Tick> recorder, FeedHealth health) {
        this.recorder = recorder;
        this.health = health;
        this.builder = new CandleBuilder(feed, basis, grace, new CandleBuilder.Listener() {
            @Override public void onCandleClosed(Candle c) { candleSink.accept(c); }
            @Override public void onLateTick(Tick t, Instant boundary) { if (lateSink != null) lateSink.onLate(t, boundary); }
        });
    }

    public interface LateTickSink {
        void onLate(Tick tick, Instant boundary);
    }

    public void onTick(Tick t) {
        ticks++;
        buffer.add(t);
        if (recorder != null) recorder.accept(t);
        if (health != null) health.onTick(t);
        builder.onTick(t);
    }

    public void onTime(Instant now) {
        builder.onTime(now);
        if (health != null) health.check(now);
    }

    /** Data before t may be missing (connect, disconnect, replay start): bars started before t are dropped. */
    public void markIncompleteBefore(Instant t) { builder.markIncompleteBefore(t); }

    public long incompleteDropped() { return builder.incompleteDropped(); }

    /** End of a replay window: emits bars ending at or before {@code end}, drops the incomplete rest. */
    public void finishAt(Instant end) { builder.finishAt(end); }

    public long ticks() { return ticks; }

    public long lateTicks(String symbol) { return builder.lateTicks(symbol); }

    public TickBuffer buffer() { return buffer; }
}
