package dev.forexsignals.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sessions in IANA zones. 2024 transitions: US 10 Mar / 3 Nov, UK 31 Mar / 27 Oct, so there are two
 * windows each year where only one of the two zones has switched; both are tested separately.
 */
class SessionCalendarTest {

    private static Instant at(String s) { return Instant.parse(s); }

    @ParameterizedTest(name = "{0} at {1}: london={2} newYork={3}")
    @CsvSource({
            // winter: London 08-17Z, New York 13-22Z
            "winter,                     2024-01-17T07:59:59Z, false, false",
            "winter,                     2024-01-17T08:00:00Z, true,  false",
            "winter,                     2024-01-17T12:59:59Z, true,  false",
            "winter,                     2024-01-17T13:00:00Z, true,  true",
            "winter,                     2024-01-17T16:59:59Z, true,  true",
            "winter,                     2024-01-17T17:00:00Z, false, true",
            "winter,                     2024-01-17T21:59:59Z, false, true",
            "winter,                     2024-01-17T22:00:00Z, false, false",
            // only New York switched (11-29 Mar): NY opens 12:00Z, London still 08:00Z
            "NY on EDT; London on GMT,   2024-03-20T12:00:00Z, true,  true",
            "NY on EDT; London on GMT,   2024-03-20T07:30:00Z, false, false",
            "NY on EDT; London on GMT,   2024-03-20T21:00:00Z, false, false",
            "week before US switch,      2024-03-06T12:30:00Z, true,  false",
            // both on summer time: London 07-16Z, NY 12-21Z
            "both summer,                2024-04-03T07:30:00Z, true,  false",
            "both summer,                2024-04-03T16:00:00Z, false, true",
            "UK still GMT on 27 Mar,     2024-03-27T07:30:00Z, false, false",
            // only London switched back (28 Oct - 1 Nov): London 08:00Z, NY still 12:00Z
            "London on GMT; NY on EDT,   2024-10-30T07:30:00Z, false, false",
            "London on GMT; NY on EDT,   2024-10-30T12:00:00Z, true,  true",
            "London on GMT; NY on EDT,   2024-10-30T16:30:00Z, true,  true",
            "London on BST a week before,2024-10-23T07:30:00Z, true,  false",
            "both standard again,        2024-11-06T12:30:00Z, true,  false",
            // weekends
            "Saturday,                   2024-01-20T12:00:00Z, false, false",
            "Sunday,                     2024-01-21T14:00:00Z, false, false"
    })
    void sessionsFollowEachZonesOwnDst(String label, String t, boolean london, boolean ny) {
        assertThat(SessionCalendar.londonOpen(at(t))).as(label + " london").isEqualTo(london);
        assertThat(SessionCalendar.newYorkOpen(at(t))).as(label + " new york").isEqualTo(ny);
    }

    @Test
    void fridayCutoffIsTwoHoursBeforeTheNewYorkClose() {
        assertThat(SessionCalendar.entryAllowed(at("2024-01-19T19:59:59Z"))).isTrue();   // 14:59:59 EST
        assertThat(SessionCalendar.entryAllowed(at("2024-01-19T20:00:00Z"))).isFalse();  // 15:00 EST
        assertThat(SessionCalendar.entryAllowed(at("2024-07-19T18:59:59Z"))).isTrue();   // 14:59:59 EDT
        assertThat(SessionCalendar.entryAllowed(at("2024-07-19T19:00:00Z"))).isFalse();  // 15:00 EDT
        assertThat(SessionCalendar.afterFridayCutoff(at("2024-01-18T20:30:00Z"))).as("Thursday").isFalse();
    }

    @Test
    void noEntriesOnWeekendOrSundayEveningOutsideSessions() {
        assertThat(SessionCalendar.entryAllowed(at("2024-01-20T12:00:00Z"))).isFalse();  // Saturday
        assertThat(SessionCalendar.entryAllowed(at("2024-01-21T22:30:00Z"))).isFalse();  // Sunday 17:30 EST: market open, no session
        assertThat(SessionCalendar.entryAllowed(at("2024-01-22T08:00:00Z"))).isTrue();   // Monday London open
    }

    @Test
    void spreadBucketsWithRolloverFirst() {
        assertThat(SessionCalendar.bucket(at("2024-01-17T21:54:59Z"))).isEqualTo(SessionCalendar.Bucket.NEW_YORK);
        assertThat(SessionCalendar.bucket(at("2024-01-17T21:55:00Z"))).isEqualTo(SessionCalendar.Bucket.ROLLOVER);  // 16:55 EST
        assertThat(SessionCalendar.bucket(at("2024-01-17T22:09:59Z"))).isEqualTo(SessionCalendar.Bucket.ROLLOVER);
        assertThat(SessionCalendar.bucket(at("2024-01-17T22:10:00Z"))).isEqualTo(SessionCalendar.Bucket.ASIA);
        assertThat(SessionCalendar.bucket(at("2024-01-17T14:00:00Z"))).isEqualTo(SessionCalendar.Bucket.OVERLAP);
        assertThat(SessionCalendar.bucket(at("2024-01-17T09:00:00Z"))).isEqualTo(SessionCalendar.Bucket.LONDON);
        assertThat(SessionCalendar.bucket(at("2024-07-17T20:55:00Z"))).isEqualTo(SessionCalendar.Bucket.ROLLOVER);  // 16:55 EDT
    }

    @Test
    void displayTimesInEatAndBrokerServerTime() {
        assertThat(SessionCalendar.eat(at("2024-01-17T12:00:00Z"))).isEqualTo("2024-01-17 15:00");
        assertThat(SessionCalendar.server(at("2024-01-17T12:00:00Z"))).isEqualTo("2024-01-17 14:00"); // UTC+2 in winter
        assertThat(SessionCalendar.server(at("2024-07-17T12:00:00Z"))).isEqualTo("2024-07-17 15:00"); // UTC+3 in summer
    }

    @Test
    void tradingHoursProfiles() {
        assertThat(TradingHours.FX.isOpen(at("2024-01-21T21:59:59Z"))).isFalse();   // Sun 16:59:59 EST
        assertThat(TradingHours.FX.isOpen(at("2024-01-21T22:00:00Z"))).isTrue();    // Sun 17:00 EST
        assertThat(TradingHours.FX.isOpen(at("2024-01-19T21:59:59Z"))).isTrue();    // Fri 16:59:59 EST
        assertThat(TradingHours.FX.isOpen(at("2024-01-19T22:00:00Z"))).isFalse();   // Fri 17:00 EST
        assertThat(TradingHours.FX.isOpen(at("2024-01-17T22:30:00Z"))).isTrue();    // Wed 17:30: FX has no daily break
        assertThat(TradingHours.METAL.isOpen(at("2024-01-17T22:30:00Z"))).isFalse(); // metals: 17:00-18:00 NY break
        assertThat(TradingHours.METAL.isOpen(at("2024-01-17T23:00:00Z"))).isTrue();
        assertThat(TradingHours.METAL.isOpen(at("2024-01-21T22:30:00Z"))).isFalse(); // metals open Sunday 18:00 NY
    }
}
