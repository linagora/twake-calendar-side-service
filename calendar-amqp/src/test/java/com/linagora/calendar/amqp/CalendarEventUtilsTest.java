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

import java.time.Clock;
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
    void shouldNotBeExpiredWhenDtStartMissing() {
        VEvent vEvent = parseSingleVEvent("""
            BEGIN:VEVENT
            UID:no-dtstart@test
            RRULE:FREQ=DAILY;COUNT=2
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
