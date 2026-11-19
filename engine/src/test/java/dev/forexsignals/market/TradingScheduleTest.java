package dev.forexsignals.market;

import dev.forexsignals.history.GapReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Broker schedules as read from the Pepperstone cTrader demo specs (2026-11-19), America/New_York, seconds from Sunday 00:00. */
class TradingScheduleTest {

    private static final int D = 86_400;

    private static Map<String, Object> spec(int[][] iv) {
        return Map.of("timeZone", "America/New_York", "intervals",
                java.util.Arrays.stream(iv).map(i -> Map.<String, Object>of("startSecond", i[0], "endSecond", i[1])).toList());
    }

    private static int at(int day, int h, int m) { return day * D + h * 3600 + m * 60; }

    // EURUSD: Sun 17:01-Mon 16:59, ... Thu 17:01-Fri 16:55
    static final int[][] FX = {{at(0, 17, 1), at(1, 16, 59)}, {at(1, 17, 1), at(2, 16, 59)}, {at(2, 17, 1), at(3, 16, 59)},
            {at(3, 17, 1), at(4, 16, 59)}, {at(4, 17, 1), at(5, 16, 55)}};
    // USDX: Sun 18:00-Mon 16:59, then Mon..Thu 20:00 - next day 16:59 (Fri 16:55)
    static final int[][] USDX = {{at(0, 18, 0), at(1, 16, 59)}, {at(1, 20, 0), at(2, 16, 59)}, {at(2, 20, 0), at(3, 16, 59)},
            {at(3, 20, 0), at(4, 16, 59)}, {at(4, 20, 0), at(5, 16, 55)}};

    @Test
    void openAndClosedFollowTheBrokerIntervalsAcrossDst() {
        TradingSchedule fx = TradingSchedule.fromSpec(spec(FX)).orElseThrow();
        assertThat(fx.isOpen(Instant.parse("2024-01-17T15:00:00Z"))).isTrue();          // Wed 10:00 EST
        assertThat(fx.isOpen(Instant.parse("2024-01-17T22:00:00Z"))).isFalse();         // Wed 17:00 EST, 2-min daily break
        assertThat(fx.isOpen(Instant.parse("2024-01-17T22:01:00Z"))).isTrue();          // 17:01
        assertThat(fx.isOpen(Instant.parse("2024-01-19T21:55:00Z"))).isFalse();         // Fri 16:55 EST close
        assertThat(fx.isOpen(Instant.parse("2024-07-19T20:54:59Z"))).isTrue();          // Fri 16:54:59 EDT
        assertThat(fx.isOpen(Instant.parse("2024-01-20T12:00:00Z"))).isFalse();         // Saturday
    }

    @Test
    void usdxThreeHourDailyCloseIsAnExpectedGap() {
        TradingSchedule usdx = TradingSchedule.fromSpec(spec(USDX)).orElseThrow();
        assertThat(usdx.isOpen(Instant.parse("2024-01-17T23:00:00Z"))).isFalse();       // Wed 18:00 EST: closed until 20:00
        assertThat(usdx.isOpen(Instant.parse("2024-01-18T01:00:00Z"))).isTrue();        // Wed 20:00 EST
        GapReport.Gap g = GapReport.classify(Instant.parse("2024-01-17T21:58:00Z"), Instant.parse("2024-01-18T01:00:00Z"), usdx);
        assertThat(g.expected()).isTrue();
        assertThat(g.reason()).isEqualTo("DAILY_BREAK");
        // With the default FX profile the same gap would be wrongly unexpected.
        assertThat(GapReport.classify(Instant.parse("2024-01-17T21:58:00Z"), Instant.parse("2024-01-18T01:00:00Z"), TradingHours.FX).expected()).isFalse();
    }

    @Test
    void missingOrEmptyScheduleFallsBack() {
        assertThat(TradingSchedule.fromSpec(null)).isEmpty();
        assertThat(TradingSchedule.fromSpec(Map.of("timeZone", "America/New_York", "intervals", List.of()))).isEmpty();
        assertThat(TradingSchedule.fromSpec(Map.of("profile", "FX"))).isEmpty();
    }
}
