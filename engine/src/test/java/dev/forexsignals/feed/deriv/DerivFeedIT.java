package dev.forexsignals.feed.deriv;

import dev.forexsignals.feed.FeedListener;
import dev.forexsignals.market.Candle;
import dev.forexsignals.market.SymbolCatalog;
import dev.forexsignals.market.Tick;
import dev.forexsignals.market.Timeframe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live check against the Deriv public endpoint (network). Deriv market data needs no credentials, so this
 * is opt-in instead: set FOREXSIGNALS_FEED_IT=1. Otherwise it is reported as skipped.
 */
@Tag("needs-feed")
class DerivFeedIT {

    @BeforeAll
    static void optIn() {
        assumeTrue("1".equals(System.getenv("FOREXSIGNALS_FEED_IT")), "set FOREXSIGNALS_FEED_IT=1 to run network feed tests");
    }

    @Test
    void symbolsHistoryAndLiveTicks() throws Exception {
        var cat = SymbolCatalog.load(Optional.empty());
        var eur = cat.get("EURUSD");
        try (DerivAdapter d = new DerivAdapter("wss://api.derivws.com/trading/v1/options/ws/public", "")) {
            var specs = d.loadSymbols(cat.onFeed("deriv", Optional.empty()));
            assertThat(specs).containsKeys("EURUSD", "GBPUSD", "USDJPY", "AUDUSD");
            assertThat(specs.get("EURUSD").digits()).isEqualTo(5);

            // A recent weekday hour of M1 candles, all inside the window and minute-aligned.
            Instant to = lastWeekdayNoon();
            List<Candle> m1 = d.fetchM1(eur, to.minus(Duration.ofHours(1)), to);
            assertThat(m1).isNotEmpty().allSatisfy(c -> {
                assertThat(c.openTime()).isBetween(to.minus(Duration.ofHours(1)), to.minusSeconds(60));
                assertThat(c.openTime().getEpochSecond() % 60).isZero();
            });

            // Live: a few ticks with bid, ask and quote.
            List<Tick> ticks = new CopyOnWriteArrayList<>();
            d.start(List.of(eur), specs, new FeedListener() {
                @Override public void onTick(Tick t) { ticks.add(t); }
                @Override public void onStatus(Status s, String detail) {}
            });
            Instant deadline = Instant.now().plusSeconds(30);
            while (ticks.size() < 3 && Instant.now().isBefore(deadline)) Thread.sleep(200);
            if (dev.forexsignals.market.TradingHours.FX.isOpen(Instant.now())) {
                assertThat(ticks).hasSizeGreaterThanOrEqualTo(3);
                assertThat(ticks.getFirst().hasBid()).isTrue();
                assertThat(ticks.getFirst().hasAsk()).isTrue();
            }
        }
    }

    static Instant lastWeekdayNoon() {
        Instant t = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(Duration.ofDays(1)).plus(Duration.ofHours(12));
        while (!dev.forexsignals.market.TradingHours.FX.isOpen(t)) t = t.minus(Duration.ofDays(1));
        return t;
    }
}
