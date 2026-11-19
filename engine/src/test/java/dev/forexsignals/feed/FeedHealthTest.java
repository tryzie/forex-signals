package dev.forexsignals.feed;

import dev.forexsignals.market.SymbolCatalog;
import dev.forexsignals.market.Tick;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Stale detection follows trading hours: no alerts on weekends or in the metals' daily break. */
class FeedHealthTest {

    private final List<String> events = new ArrayList<>();
    private final SymbolCatalog cat = SymbolCatalog.load(Optional.empty());

    private FeedHealth health(Instant start) {
        Map<String, SymbolCatalog.SymbolDef> syms = new LinkedHashMap<>();
        syms.put("EURUSD", cat.get("EURUSD"));
        syms.put("XAUUSD", cat.get("XAUUSD"));
        return new FeedHealth("test", syms, Duration.ofSeconds(60), Duration.ofSeconds(120), start,
                (ts, feed, sym, kind, detail) -> events.add(kind + ":" + sym));
    }

    private static Tick tick(String sym, Instant t) { return new Tick("test", sym, t, 1.0, 1.0001, t, 0); }

    @Test
    void staleOnceWhileOpenThenRecovered() {
        Instant t0 = Instant.parse("2024-01-17T10:00:00Z"); // Wednesday
        FeedHealth h = health(t0);
        h.onTick(tick("EURUSD", t0));
        h.onTick(tick("XAUUSD", t0));
        h.check(t0.plusSeconds(60));
        assertThat(events).isEmpty();
        h.check(t0.plusSeconds(61));
        assertThat(events).containsExactly("STALE:EURUSD");          // metals allow 120 s
        h.check(t0.plusSeconds(90));
        assertThat(events).containsExactly("STALE:EURUSD");          // reported once per episode
        assertThat(h.isStale("EURUSD")).isTrue();
        h.check(t0.plusSeconds(121));
        assertThat(events).containsExactly("STALE:EURUSD", "STALE:XAUUSD");
        h.onTick(tick("EURUSD", t0.plusSeconds(130)));
        assertThat(events).endsWith("RECOVERED:EURUSD");
        assertThat(h.isStale("EURUSD")).isFalse();
    }

    @Test
    void noStaleAlertsOnTheWeekendOrInTheMetalsBreak() {
        Instant fri = Instant.parse("2024-01-19T21:59:00Z");        // Fri 16:59 EST
        FeedHealth h = health(fri);
        h.onTick(tick("EURUSD", fri));
        h.onTick(tick("XAUUSD", fri));
        for (Instant t = fri; t.isBefore(Instant.parse("2024-01-21T22:00:00Z")); t = t.plusSeconds(600)) h.check(t);
        assertThat(events).isEmpty();
        // Sunday open at 17:00 NY is inside the rollover window (16:58-17:05), where silence is normal; the first
        // alert can come one stale window after it, and never from Friday's last tick.
        h.check(Instant.parse("2024-01-21T22:01:01Z"));
        h.check(Instant.parse("2024-01-21T22:05:59Z"));
        assertThat(events).isEmpty();
        h.check(Instant.parse("2024-01-21T22:06:00Z"));
        assertThat(events).containsExactly("STALE:EURUSD");          // metals open at 18:00 NY = 23:00Z
    }

    @Test
    void dailyRolloverPauseIsNotStale() {
        Instant t0 = Instant.parse("2024-01-17T21:57:30Z");          // 16:57:30 EST, last tick before rollover
        FeedHealth h = health(t0);
        h.onTick(tick("EURUSD", t0));
        h.onTick(tick("XAUUSD", t0));
        for (Instant t = t0; t.isBefore(Instant.parse("2024-01-17T22:06:00Z")); t = t.plusSeconds(5)) h.check(t); // to 17:06 EST
        assertThat(events).isEmpty();
        h.check(Instant.parse("2024-01-17T22:06:01Z"));              // a full minute after rollover ended, still silent
        assertThat(events).containsExactly("STALE:EURUSD");
    }

    @Test
    void connectionStatusIsRecorded() {
        FeedHealth h = health(Instant.parse("2024-01-17T10:00:00Z"));
        h.onStatus(FeedListener.Status.CONNECTED, "ok", Instant.parse("2024-01-17T10:00:00Z"));
        h.onStatus(FeedListener.Status.DISCONNECTED, "eof", Instant.parse("2024-01-17T10:05:00Z"));
        assertThat(events).containsExactly("CONNECTED:null", "DISCONNECTED:null");
        assertThat(h.connected()).isFalse();
    }
}
