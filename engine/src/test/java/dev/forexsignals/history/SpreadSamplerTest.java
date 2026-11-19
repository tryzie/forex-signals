package dev.forexsignals.history;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SpreadSamplerTest {

    @Test
    void oneWeekPerQuarterOnTheSecondSundayOfTheMiddleMonth() {
        var weeks = SpreadSampler.weeks(2024, Instant.parse("2026-11-19T00:00:00Z"));
        assertThat(weeks).hasSize(12);
        assertThat(weeks.getFirst().label()).isEqualTo("2024Q1");
        assertThat(weeks.getFirst().from()).isEqualTo(Instant.parse("2024-02-11T00:00:00Z"));
        assertThat(weeks.getLast().label()).isEqualTo("2026Q4");
        assertThat(weeks.getLast().from()).isEqualTo(Instant.parse("2026-11-08T00:00:00Z")); // the week replayed in 1b
        assertThat(weeks).allSatisfy(w -> {
            assertThat(w.from().atZone(ZoneOffset.UTC).getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
            assertThat(w.from().atZone(ZoneOffset.UTC).getDayOfMonth()).isBetween(8, 14);
            assertThat(Duration.between(w.from(), w.to())).isEqualTo(Duration.ofDays(6));
        });
        // A quarter whose sample week has not finished yet is left out.
        assertThat(SpreadSampler.weeks(2024, Instant.parse("2026-11-13T00:00:00Z"))).hasSize(11);
    }
}
