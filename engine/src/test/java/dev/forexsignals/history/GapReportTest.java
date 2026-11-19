package dev.forexsignals.history;

import dev.forexsignals.market.TradingHours;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GapReportTest {

    private static Instant at(String s) { return Instant.parse(s); }

    @Test
    void weekendGapIsExpected() {
        // Last bar Fri 16:59 EST, next bar Sun 17:00 EST.
        GapReport.Gap g = GapReport.classify(at("2024-01-19T21:59:00Z"), at("2024-01-21T22:00:00Z"), TradingHours.FX);
        assertThat(g.expected()).isTrue();
        assertThat(g.reason()).isEqualTo("WEEKEND");
        assertThat(g.openMinutes()).isZero();
        assertThat(g.minutes()).isEqualTo(2 * 24 * 60);
    }

    @Test
    void weekendGapThatRunsIntoTheOpenIsUnexpected() {
        // Data resumes at Sun 17:07 instead of 17:00: 7 open minutes are missing (17:00-17:04 are rollover, 17:05-17:06 are not).
        GapReport.Gap g = GapReport.classify(at("2024-01-19T21:59:00Z"), at("2024-01-21T22:07:00Z"), TradingHours.FX);
        assertThat(g.expected()).isFalse();
        assertThat(g.openMinutes()).isEqualTo(7);
        assertThat(g.reason()).isEqualTo("NO_TICKS");
    }

    @Test
    void metalsDailyBreakIsExpectedButNotForFx() {
        // Wed 16:59 EST -> 18:00 EST.
        GapReport.Gap metal = GapReport.classify(at("2024-01-17T21:59:00Z"), at("2024-01-17T23:00:00Z"), TradingHours.METAL);
        assertThat(metal.expected()).isTrue();
        assertThat(metal.reason()).isEqualTo("DAILY_BREAK");
        GapReport.Gap fx = GapReport.classify(at("2024-01-17T21:59:00Z"), at("2024-01-17T23:00:00Z"), TradingHours.FX);
        assertThat(fx.expected()).isFalse();
    }

    @Test
    void shortRolloverGapIsExpected() {
        // Missing 16:59-17:03 EST only.
        GapReport.Gap g = GapReport.classify(at("2024-01-17T21:58:00Z"), at("2024-01-17T22:04:00Z"), TradingHours.FX);
        assertThat(g.expected()).isTrue();
        assertThat(g.reason()).isEqualTo("ROLLOVER");
    }

    @Test
    void longMidweekOutageIsFlaggedForManualCheck() {
        GapReport.Gap g = GapReport.classify(at("2024-12-24T18:00:00Z"), at("2024-12-25T23:00:00Z"), TradingHours.FX);
        assertThat(g.expected()).isFalse();
        assertThat(g.reason()).isEqualTo("LONG_OUTAGE_OR_HOLIDAY");
    }
}
