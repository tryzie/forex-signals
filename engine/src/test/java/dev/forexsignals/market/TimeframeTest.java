package dev.forexsignals.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Bar alignment: UTC minutes for M1-M15, the 17:00 New York trading day for H1/H4/D1 (PLAN §5). */
class TimeframeTest {

    private static Instant at(String s) { return Instant.parse(s); }

    @Test
    void minuteTimeframesAlignToUtc() {
        assertThat(Timeframe.M1.bucketStart(at("2024-01-17T10:03:59.999Z"))).isEqualTo(at("2024-01-17T10:03:00Z"));
        assertThat(Timeframe.M5.bucketStart(at("2024-01-17T10:04:59Z"))).isEqualTo(at("2024-01-17T10:00:00Z"));
        assertThat(Timeframe.M5.bucketStart(at("2024-01-17T10:05:00Z"))).isEqualTo(at("2024-01-17T10:05:00Z"));
        assertThat(Timeframe.M15.bucketStart(at("2024-01-17T10:44:00Z"))).isEqualTo(at("2024-01-17T10:30:00Z"));
        assertThat(Timeframe.M15.bucketEnd(at("2024-01-17T10:30:00Z"))).isEqualTo(at("2024-01-17T10:45:00Z"));
    }

    // H4 bars open at 17, 21, 01, 05, 09, 13 New York time. The UTC hour therefore moves with US daylight
    // saving, and only with US daylight saving (the UK switching on another date changes nothing here).
    @ParameterizedTest(name = "{0}: H4 containing {1} starts {2}")
    @CsvSource({
            "US winter (EST),                       2024-01-17T15:30:00Z, 2024-01-17T14:00:00Z, 2024-01-17T18:00:00Z",
            "US summer (EDT),                       2024-07-17T15:30:00Z, 2024-07-17T13:00:00Z, 2024-07-17T17:00:00Z",
            "US switched 10 Mar but UK not yet,     2024-03-20T15:30:00Z, 2024-03-20T13:00:00Z, 2024-03-20T17:00:00Z",
            "week before US switch,                 2024-03-06T15:30:00Z, 2024-03-06T14:00:00Z, 2024-03-06T18:00:00Z",
            "UK back on GMT 27 Oct but US on EDT,   2024-10-30T15:30:00Z, 2024-10-30T13:00:00Z, 2024-10-30T17:00:00Z",
            "both back on standard time,            2024-11-06T15:30:00Z, 2024-11-06T14:00:00Z, 2024-11-06T18:00:00Z",
            "bar opening at 17:00 NY (winter),      2024-01-17T22:00:00Z, 2024-01-17T22:00:00Z, 2024-01-18T02:00:00Z",
            "bar opening at 01:00 NY (winter),      2024-01-18T06:59:59Z, 2024-01-18T06:00:00Z, 2024-01-18T10:00:00Z"
    })
    void h4FollowsNewYorkTime(String label, String t, String start, String end) {
        Instant s = Timeframe.H4.bucketStart(at(t));
        assertThat(s).as(label).isEqualTo(at(start));
        assertThat(Timeframe.H4.bucketEnd(s)).as(label).isEqualTo(at(end));
    }

    @Test
    void d1StartsAt1700NewYork() {
        assertThat(Timeframe.D1.bucketStart(at("2024-01-17T15:30:00Z"))).isEqualTo(at("2024-01-16T22:00:00Z"));
        assertThat(Timeframe.D1.bucketEnd(at("2024-01-16T22:00:00Z"))).isEqualTo(at("2024-01-17T22:00:00Z"));
        assertThat(Timeframe.D1.bucketStart(at("2024-07-17T15:30:00Z"))).isEqualTo(at("2024-07-16T21:00:00Z"));
        // Sunday open on the US DST change day: 17:00 EDT = 21:00Z.
        assertThat(Timeframe.D1.bucketStart(at("2024-03-10T21:00:30Z"))).isEqualTo(at("2024-03-10T21:00:00Z"));
        // The Friday before: the trading day ran Thu 17:00 EST to Fri 17:00 EST.
        assertThat(Timeframe.D1.bucketStart(at("2024-03-08T21:59:59Z"))).isEqualTo(at("2024-03-07T22:00:00Z"));
    }

    @Test
    void h1MatchesUtcHoursBecauseNewYorkOffsetsAreWholeHours() {
        assertThat(Timeframe.H1.bucketStart(at("2024-01-17T15:59:59Z"))).isEqualTo(at("2024-01-17T15:00:00Z"));
        assertThat(Timeframe.H1.bucketStart(at("2024-07-17T15:00:00Z"))).isEqualTo(at("2024-07-17T15:00:00Z"));
    }

    // Bars must tile time with no holes or overlaps, including across both DST weekends.
    @ParameterizedTest
    @CsvSource({"2024-03-07T00:00:00Z, 2024-03-13T00:00:00Z", "2024-10-31T00:00:00Z, 2024-11-06T00:00:00Z",
                "2024-03-28T00:00:00Z, 2024-04-02T00:00:00Z", "2024-10-25T00:00:00Z, 2024-10-29T00:00:00Z"})
    void barsTileTimeAcrossDstWeekends(String from, String to) {
        for (Timeframe tf : Timeframe.values()) {
            Instant s = tf.bucketStart(at(from));
            while (s.isBefore(at(to))) {
                Instant e = tf.bucketEnd(s);
                assertThat(e).as(tf + " end after start at " + s).isAfter(s);
                assertThat(tf.bucketStart(e)).as(tf + " next bar starts where " + s + " ends").isEqualTo(e);
                assertThat(tf.bucketStart(e.minusMillis(1))).as(tf + " last ms belongs to bar " + s).isEqualTo(s);
                assertThat(Duration.between(s, e).toHours()).as(tf + " length").isLessThanOrEqualTo(25);
                s = e;
            }
        }
    }
}
