/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.calendar.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.linagora.calendar.api.CalendarUtil;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;

class CalendarEventUtilsTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void shouldBeExpiredWhenDtStartIsInPast() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:past-single@test
            DTSTART:20000101T100000Z
            DTEND:20000101T110000Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isTrue();
    }

    @Test
    void shouldNotBeExpiredWhenDtStartIsInFuture() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:future-single@test
            DTSTART:29990101T100000Z
            DTEND:29990101T110000Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldBeExpiredEvenWhenDtEndAvailable() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:past-with-dtend@test
            DTSTART:20251230T100000Z
            DTEND:20260120T100000Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isTrue();
    }

    @Test
    void shouldNotBeExpiredWhenRRuleIsInfinite() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:infinite-recurring@test
            DTSTART:20000101T100000Z
            RRULE:FREQ=DAILY
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldBeExpiredWhenRRuleWithCountAndAllOccurrencesInPast() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rrule-count-all-past@test
            DTSTART:20251220T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isTrue();
    }

    @Test
    void shouldNotBeExpiredWhenRRuleWithCountAndAllOccurrencesInFuture() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rrule-count-all-future@test
            DTSTART:20260110T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldNotBeExpiredWhenRRuleWithCountAndOneOccurrenceInFuture() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rrule-count-one-future@test
            DTSTART:20251230T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldNotBeExpiredWhenDtStartIsInFutureAndNotMatchingByMonth() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:unsynchronized-future@test
            DTSTART:20261219T171700Z
            RRULE:FREQ=YEARLY;COUNT=3;BYMONTH=1
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldNotBeExpiredWhenUnsynchronizedDtStartIsPastButRuleHasUpcomingOccurrence() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:unsynchronized-past@test
            DTSTART:20251219T171700Z
            RRULE:FREQ=YEARLY;COUNT=3;BYMONTH=1
            END:VEVENT
            """);

        // 2026-01-19T17:17Z and 2027-01-19T17:17Z are after TEST_CLOCK
        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldNotBeExpiredWhenUnsynchronizedDtStartIsInFutureWithBySetPos() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:unsynchronized-bysetpos@test
            DTSTART:20261219T171700Z
            RRULE:FREQ=YEARLY;COUNT=85;BYMONTH=1,7;BYMINUTE=0,15,30,45;BYSETPOS=1,3
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldBeExpiredWhenUnsynchronizedDtStartAndAllOccurrencesInPast() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:unsynchronized-all-past@test
            DTSTART:20221219T171700Z
            RRULE:FREQ=YEARLY;COUNT=2;BYMONTH=1
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isTrue();
    }

    @Test
    void shouldBeExpiredWhenFutureUnsynchronizedDtStartIsExcludedAndNoOtherOccurrence() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:unsynchronized-excluded@test
            DTSTART:20261219T171700Z
            RRULE:FREQ=YEARLY;UNTIL=20261231T000000Z;BYMONTH=1
            EXDATE:20261219T171700Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isTrue();
    }

    @Test
    void shouldNotBeExpiredWhenOnlyRDateHasFutureOccurrence() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rdate-only@test
            DTSTART:20251220T100000Z
            RDATE:20260110T100000Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldBeExpiredWhenOnlyRDateOccurrencesAreInPast() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rdate-only-all-past@test
            DTSTART:20251220T100000Z
            RDATE:20251221T100000Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isTrue();
    }

    @Test
    void shouldNotBeExpiredWhenRRuleIsPastButHasFutureRDate() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rrule-with-rdate@test
            DTSTART:20251220T100000Z
            RRULE:FREQ=DAILY;COUNT=2
            RDATE:20260110T100000Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldBeExpiredWhenRRuleWithUntilInPast() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rrule-until-past@test
            DTSTART:20251220T100000Z
            RRULE:FREQ=DAILY;UNTIL=20251225T100000Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isTrue();
    }

    @Test
    void shouldNotBeExpiredWhenRRuleWithUntilInFuture() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rrule-until-future@test
            DTSTART:20251220T100000Z
            RRULE:FREQ=DAILY;UNTIL=20260110T100000Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldBeExpiredWhenAllDayRRuleWithCountAllInPast() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:all-day-rrule-past@test
            DTSTART;VALUE=DATE:20251220
            RRULE:FREQ=DAILY;COUNT=3
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isTrue();
    }

    @Test
    void shouldNotBeExpiredWhenAllDayRRuleWithCountHasFutureOccurrence() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:all-day-rrule-future@test
            DTSTART;VALUE=DATE:20251231
            RRULE:FREQ=DAILY;COUNT=3
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldBeExpiredWhenRRuleHasOnlyPastRDate() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rrule-rdate-only-past@test
            DTSTART:20251220T100000Z
            RRULE:FREQ=DAILY;COUNT=2
            RDATE:20251222T100000Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isTrue();
    }

    @Test
    void shouldBeExpiredWhenFutureRRuleOccurrenceIsExcludedByExDate() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rrule-future-excluded@test
            DTSTART:20251230T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            EXDATE:20260101T100000Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isTrue();
    }

    @Test
    void shouldNotBeExpiredWhenRRuleIsInfiniteEvenIfRDateIsPast() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rrule-infinite-rdate-past@test
            DTSTART:20251220T100000Z
            RRULE:FREQ=WEEKLY
            RDATE:20251222T100000Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldBeExpiredWhenRRuleValueDoesNotProduceFutureOccurrence() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rrule-invalid@test
            DTSTART:20251220T100000Z
            RRULE:THIS-IS-NOT-A-VALID-RRULE
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isTrue();
    }

    @Test
    void shouldIgnoreUnparsableExDate() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:event-uid@test
            DTSTART;TZID=Europe/Paris:20261013T140000
            RRULE:FREQ=WEEKLY;BYDAY=TU;COUNT=10
            EXDATE;TZID=Europe/Paris:20261340T140000
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldStillApplyReadableExDateWhenAnotherExDateIsUnparsable() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rrule-future-excluded-with-garbage@test
            DTSTART:20251230T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            EXDATE:FREQ=YEARLY00
            EXDATE:20260101T100000Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isTrue();
    }

    @Test
    void shouldIgnoreUnparsableRDate() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rdate-garbage@test
            DTSTART:20251220T100000Z
            RDATE:14505207T140000
            RDATE:20260110T100000Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldNotBeExpiredWhenOnlyRDateIsUnreadable() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rdate-expiry-unknown@test
            DTSTART:20251220T100000Z
            RDATE;TZID=Europe/Paris:20261340T100000
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldNotBeExpiredWhenUnreadableRDateComesWithPastRDates() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rdate-expiry-unknown-with-past@test
            DTSTART:20251220T100000Z
            RDATE:20251221T100000Z
            RDATE;TZID=Europe/Paris:20261340T100000
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldNotBeExpiredWhenExDatePropertyMixingReadableAndUnparsableDatesMayHideAnUpcomingOccurrence() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:exdate-mixed-values@test
            DTSTART:20251230T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            EXDATE;TZID=Europe/Paris:20260101T110000,20261340T110000
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldNotBeExpiredWhenSameRDatePropertyContainsFutureAndUnparsableDates() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rdate-mixed-values@test
            DTSTART:20251220T100000Z
            RDATE;TZID=Europe/Paris:20260110T110000,20261340T110000
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldNotBeExpiredWhenPeriodRDateIsInFuture() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rdate-period@test
            DTSTART:20251220T100000Z
            RDATE;VALUE=PERIOD:20260110T100000Z/PT1H
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldBeExpiredWhenPeriodRDateIsInPast() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rdate-period-past@test
            DTSTART:20251210T100000Z
            RDATE;VALUE=PERIOD:20251220T100000Z/20251220T110000Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isTrue();
    }

    @Test
    void shouldBeExpiredWhenPeriodRDateIsExcluded() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rdate-period-excluded@test
            DTSTART:20251210T100000Z
            RDATE;VALUE=PERIOD:20260110T100000Z/PT1H
            EXDATE:20260110T100000Z
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isTrue();
    }

    @Test
    void shouldNotBeExpiredWhenOnePeriodOfRDateIsInFuture() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:rdate-periods-mixed@test
            DTSTART:20251210T100000Z
            RDATE;VALUE=PERIOD:20251220T100000Z/PT1H,20260110T100000Z/PT1H
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void shouldNotBeExpiredWhenDtStartMissing() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:no-dtstart@test
            RRULE:FREQ=DAILY;COUNT=2
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    @Test
    void expiryCheckShouldStayFastForFiniteRuleWithManyPastOccurrences() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:many-past-occurrences@test
            DTSTART:20250101T000000Z
            RRULE:FREQ=SECONDLY;INTERVAL=868;COUNT=7105903
            END:VEVENT
            """);

        boolean expired = assertTimeoutPreemptively(Duration.ofSeconds(1),
            () -> CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK));
        assertThat(expired).isFalse();
    }

    @Test
    void expiryCheckShouldStayFastForUntilRuleWithManyPastOccurrences() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:many-past-occurrences@test
            DTSTART:19700101T000000Z
            RRULE:FREQ=SECONDLY;UNTIL=20991231T000000Z
            END:VEVENT
            """);

        boolean expired = assertTimeoutPreemptively(Duration.ofSeconds(1),
            () -> CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK));
        assertThat(expired).isFalse();
    }

    @Test
    void expiryCheckShouldBeFastWhenUntilIsPast() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:ended@test
            DTSTART:19700101T000000Z
            RRULE:FREQ=SECONDLY;UNTIL=20251231T000000Z
            END:VEVENT
            """);

        boolean expired = assertTimeoutPreemptively(Duration.ofSeconds(1),
            () -> CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK));
        assertThat(expired).isTrue();
    }

    @Test
    void shouldBeExpiredWhenAllOfManyOccurrencesArePast() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:many-past@test
            DTSTART:20200101T100000Z
            RRULE:FREQ=HOURLY;COUNT=15000
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isTrue();
    }

    @Test
    void shouldNotBeExpiredWhenUpcomingOccurrenceFollowsManyPastOnes() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:many-past-then-upcoming@test
            DTSTART:20250101T000000Z
            RRULE:FREQ=HOURLY;COUNT=8761
            END:VEVENT
            """);

        assertThat(CalendarEventUtils.vEventExpired(vEvent, TEST_CLOCK)).isFalse();
    }

    private VEvent parseSingleVEvent(String vEventIcs) {
        String calendarIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//CalendarEventUtilsTest//EN
            %s
            END:VCALENDAR
            """.formatted(vEventIcs);

        return CalendarUtil.parseIcs(calendarIcs).getComponents().stream()
            .filter(component -> Component.VEVENT.equals(component.getName()))
            .map(VEvent.class::cast)
            .findFirst()
            .orElseThrow();
    }
}
