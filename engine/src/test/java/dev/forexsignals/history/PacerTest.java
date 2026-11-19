package dev.forexsignals.history;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** History requests are paced at or below 4 per second (cTrader allows 5). */
class PacerTest {

    @Test
    void neverMoreThanFourRequestStartsInAnyOneSecondWindow() throws Exception {
        Instant[] now = {Instant.parse("2024-01-01T00:00:00Z")};
        Pacer p = new Pacer(4.0, () -> now[0], d -> now[0] = now[0].plus(d));
        List<Instant> starts = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            p.acquire();
            starts.add(now[0]);
            now[0] = now[0].plusMillis(i % 3 == 0 ? 0 : 37); // callers come back at irregular, often immediate, times
        }
        for (int i = 4; i < starts.size(); i++) {
            assertThat(Duration.between(starts.get(i - 4), starts.get(i))).as("5 starts span >= 1 s").isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        }
        for (int i = 1; i < starts.size(); i++) {
            assertThat(Duration.between(starts.get(i - 1), starts.get(i))).isGreaterThanOrEqualTo(Duration.ofMillis(250));
        }
        // 200 requests at 4/s take at least 49.75 s.
        assertThat(Duration.between(starts.getFirst(), starts.getLast())).isGreaterThanOrEqualTo(Duration.ofMillis(49_750));
    }

    @Test
    void slowCallersAreNotDelayed() throws Exception {
        Instant[] now = {Instant.parse("2024-01-01T00:00:00Z")};
        List<Duration> sleeps = new ArrayList<>();
        Pacer p = new Pacer(4.0, () -> now[0], d -> { sleeps.add(d); now[0] = now[0].plus(d); });
        for (int i = 0; i < 10; i++) {
            p.acquire();
            now[0] = now[0].plusMillis(400);
        }
        assertThat(sleeps).isEmpty();
    }
}
