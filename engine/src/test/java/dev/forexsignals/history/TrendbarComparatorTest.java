package dev.forexsignals.history;

import dev.forexsignals.market.Candle;
import dev.forexsignals.market.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrendbarComparatorTest {

    private static Candle m5(String t, double o, double h, double l, double c) {
        Instant s = Instant.parse(t);
        return new Candle("f", "EURUSD", Timeframe.M5, s, s.plusSeconds(300), o, h, l, c, 1, Double.NaN, Double.NaN, Double.NaN, Candle.Source.DERIVED);
    }

    @Test
    void identicalCandlesMatchDespiteFloatingPointNoise() {
        var built = List.of(m5("2024-01-17T10:00:00Z", 1.10001, 1.10010, 1.09990, 1.10005));
        var ref = List.of(m5("2024-01-17T10:00:00Z", 1.10001 + 1e-12, 1.1001, 1.0999, 1.10005));
        var r = TrendbarComparator.compare("f", "EURUSD", "M5", Instant.EPOCH, Instant.EPOCH, built, ref, 0.00001);
        assertThat(r.matched()).isEqualTo(1);
        assertThat(r.unexplained()).isZero();
    }

    @Test
    void onePointDifferenceAndMissingBarsAreReported() {
        var built = List.of(m5("2024-01-17T10:00:00Z", 1.1, 1.1, 1.1, 1.1),
                m5("2024-01-17T10:05:00Z", 1.10001, 1.1001, 1.0999, 1.1),
                m5("2024-01-17T10:10:00Z", 1.1, 1.1, 1.1, 1.1),
                m5("2024-01-17T10:20:00Z", 1.1, 1.1, 1.1, 1.1));
        var ref = List.of(m5("2024-01-17T10:00:00Z", 1.1, 1.1, 1.1, 1.1),
                m5("2024-01-17T10:05:00Z", 1.10002, 1.1001, 1.0999, 1.1),   // open differs by 1 point
                m5("2024-01-17T10:15:00Z", 1.1, 1.1, 1.1, 1.1),             // bar only in reference
                m5("2024-01-17T10:20:00Z", 1.1, 1.1, 1.1, 1.1));
        var r = TrendbarComparator.compare("f", "EURUSD", "M5", Instant.EPOCH, Instant.EPOCH, built, ref, 0.00001);
        assertThat(r.matched()).isEqualTo(2);
        assertThat(r.diffs()).extracting(TrendbarComparator.Diff::kind).containsExactly("OHLC", "ONLY_BUILT", "ONLY_REFERENCE");
        assertThat(r.diffs().getFirst().detail()).contains("O=-1");
        assertThat(r.unexplained()).isEqualTo(3);
    }

    @Test
    void edgeBarsAreExplained() {
        var built = List.of(m5("2024-01-17T10:00:00Z", 1.1, 1.1, 1.1, 1.1), m5("2024-01-17T10:05:00Z", 1.1, 1.1, 1.1, 1.1));
        var ref = List.of(m5("2024-01-17T10:05:00Z", 1.1, 1.1, 1.1, 1.1));
        var r = TrendbarComparator.compare("f", "EURUSD", "M5", Instant.EPOCH, Instant.EPOCH, built, ref, 0.00001);
        assertThat(r.diffs()).hasSize(1);
        assertThat(r.unexplained()).isZero();
    }
}
