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

package com.linagora.calendar.dav;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linagora.calendar.storage.event.AlarmInstantFactory;

import net.fortuna.ical4j.model.Calendar;

public class AlarmInstantFactoryTest {

    private AlarmInstantFactory testee(Instant clockFixedInstant) {
        return new AlarmInstantFactory.Default(Clock.fixed(clockFixedInstant, ZoneOffset.UTC));
    }

    @Test
    void shouldNotScheduleAlarm_whenAttendeeHasNotAccepted() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:123456
            DTSTART:20250829T100000Z
            SUMMARY:Test Event
            ATTENDEE;CN=John Doe:mailto:john@example.com
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT10M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        Username attendee = Username.of("john@example.com");

        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));

        Optional<Instant> result = testee.computeNextAlarmInstant(attendee, calendar);

        assertTrue(result.isEmpty(), "Alarm should not be scheduled when attendee has not accepted the invitation");
    }

    @Test
    void shouldNotScheduleAlarm_whenEventAlreadyOccurred() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:past-event
            DTSTART:20250720T100000Z
            SUMMARY:Past Event
            ATTENDEE;CN=John Doe;PARTSTAT=ACCEPTED:mailto:john@example.com
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT10M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));

        Calendar calendar = CalendarUtil.parseIcs(ics);
        Username attendee = Username.of("john@example.com");

        Optional<Instant> result = testee.computeNextAlarmInstant(attendee, calendar);

        assertTrue(result.isEmpty(), "Alarm should not be scheduled for past event");
    }

    @Test
    void shouldReturnAlarmInstant_whenAttendeeHasAcceptedAndEventIsInFuture() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:789012
            DTSTART:20250829T100000Z
            SUMMARY:Meeting
            ATTENDEE;CN=Jane Doe;PARTSTAT=ACCEPTED:mailto:jane@example.com
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        Username attendee = Username.of("jane@example.com");

        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));

        Optional<Instant> result = testee.computeNextAlarmInstant(attendee, calendar);

        // Expected: 2025-08-29T09:45:00Z (15 minutes before 10:00)
        Instant expected = Instant.parse("2025-08-29T09:45:00Z");

        assertTrue(result.isPresent(), "Alarm should be scheduled when event is in the future and attendee has accepted");
        assertEquals(expected, result.get(), "Alarm time should match the expected trigger time");
    }

    @Test
    void shouldNotScheduleAlarm_whenNoValarmPresent() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:123456
            DTSTART:20250829T100000Z
            SUMMARY:Test Event
            ATTENDEE;CN=John Doe;PARTSTAT=ACCEPTED:mailto:john@example.com
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        Username attendee = Username.of("john@example.com");

        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));

        Optional<Instant> result = testee.computeNextAlarmInstant(attendee, calendar);

        assertTrue(result.isEmpty(), "Alarm should not be scheduled when no VALARM is present");
    }

    @Test
    void shouldComputeAlarmInstantAsStartDatePlus15MinutesTrigger() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:123456
            DTSTART:20250829T100000Z
            SUMMARY:Test Event
            ATTENDEE;CN=John Doe;PARTSTAT=ACCEPTED:mailto:john@example.com
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;RELATED=START:+PT15M
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        Username attendee = Username.of("john@example.com");

        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));

        Optional<Instant> result = testee.computeNextAlarmInstant(attendee, calendar);

        assertThat(result).contains(Instant.parse("2025-08-29T10:15:00Z"));
    }

    static Stream<Arguments> invalidAlarms() {
        return Stream.of(
            Arguments.of("No TRIGGER", """
                BEGIN:VALARM
                ACTION:DISPLAY
                DESCRIPTION:Reminder
                END:VALARM""".trim()),
            Arguments.of("TRIGGER with non-duration", """
                BEGIN:VALARM
                TRIGGER:INVALID
                ACTION:DISPLAY
                DESCRIPTION:Reminder
                END:VALARM""".trim()),
            Arguments.of("TRIGGER with missing ACTION", """
                BEGIN:VALARM
                TRIGGER:-PT15M
                DESCRIPTION:Reminder
                END:VALARM""".trim()),
            Arguments.of("VALARM with no fields", """
                BEGIN:VALARM
                END:VALARM""".trim())
        );
    }

    @ParameterizedTest(name = "{index} - {0}")
    @MethodSource("invalidAlarms")
    void shouldReturnEmptyForInvalidAlarms(String description, String valarmContent) throws Exception {
        String calendarContent = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:123456
            DTSTART:20250829T100000Z
            DTEND:20250829T110000Z
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
            SUMMARY:Test Event
            %s
            END:VEVENT
            END:VCALENDAR
            """.formatted(valarmContent);

        Calendar calendar = CalendarUtil.parseIcs(calendarContent);
        Username attendee = Username.of("bob@example.com");
        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));

        Optional<Instant> result = testee.computeNextAlarmInstant(attendee, calendar);

        assertThat(result).isEmpty();
    }


    @Test
    void shouldReturnNextAlarmInstantForRecurringEvent() {
        String calendarContent = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:recurring-event
            DTSTART:20250829T100000Z
            DTEND:20250829T110000Z
            RRULE:FREQ=DAILY;COUNT=3
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
            SUMMARY:Daily Standup
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:DISPLAY
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(calendarContent);
        Username attendee = Username.of("bob@example.com");

        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));

        Optional<Instant> result = testee.computeNextAlarmInstant(attendee, calendar);

        // DTSTART = 2025-08-29T10:00:00Z → TRIGGER = -15m → Alarm = 2025-08-29T09:45:00Z
        assertThat(result).contains(Instant.parse("2025-08-29T09:45:00Z"));
    }

//    @Test
//    void testAlarmInstantFactory() {
//        String calendarData = """
//            BEGIN:VCALENDAR
//            VERSION:2.0
//            CALSCALE:GREGORIAN
//            PRODID:-//SabreDAV//SabreDAV 3.2.2//EN
//            X-WR-CALNAME:Test
//            X-APPLE-CALENDAR-COLOR:#d9ea87
//            BEGIN:VTIMEZONE
//            TZID:Asia/Ho_Chi_Minh
//            BEGIN:STANDARD
//            TZOFFSETFROM:+0700
//            TZOFFSETTO:+0700
//            TZNAME:ICT
//            DTSTART:19700101T000000
//            END:STANDARD
//            END:VTIMEZONE
//            BEGIN:VEVENT
//            UID:778ecdd2-2c15-4ab5-b7a6-9239131b441a
//            TRANSP:OPAQUE
//            DTSTART;TZID=Asia/Saigon:20250723T113000
//            DTEND;TZID=Asia/Saigon:20250723T120000
//            CLASS:PUBLIC
//            X-OPENPAAS-VIDEOCONFERENCE:
//            SUMMARY:Recurrence 4
//            RRULE:FREQ=WEEKLY;COUNT=4;BYDAY=FR
//            ORGANIZER;CN=Twake CALENDAR-DEV-2:mailto:twake-calendar-dev-2@domain.tld
//            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI
//             DUAL;CN=Van Tung TRAN;SCHEDULE-STATUS=1.1:mailto:vttran@domain.tld
//            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:t
//             wake-calendar-dev-2@domain.tld
//            DTSTAMP:20250728T065409Z
//            BEGIN:VALARM
//            TRIGGER:-PT5M
//            ACTION:EMAIL
//            ATTENDEE:mailto:twake-calendar-dev-2@domain.tld
//            SUMMARY:Recurrence 4
//            DESCRIPTION:This is an automatic alarm sent by OpenPaas\\\\nThe event Recurre
//             nce 4 will start 5 days ago\\\\nstart: Wed Jul 23 2025 11:30:00 GMT+0700 \\\\n
//             end: Wed Jul 23 2025 12:00:00 GMT+0700 \\\\nlocation:  \\\\nclass: PUBLIC \\\\n
//            END:VALARM
//            END:VEVENT
//            BEGIN:VEVENT
//            UID:778ecdd2-2c15-4ab5-b7a6-9239131b441a
//            TRANSP:OPAQUE
//            DTSTART;TZID=Asia/Saigon:20250808T130000
//            DTEND;TZID=Asia/Saigon:20250808T133000
//            CLASS:PUBLIC
//            X-OPENPAAS-VIDEOCONFERENCE:
//            SUMMARY:Recurrence 4
//            ORGANIZER;CN=Twake CALENDAR-DEV-2:mailto:twake-calendar-dev-2@domain.tld
//            DTSTAMP:20250728T065409Z
//            RECURRENCE-ID:20250808T043000Z
//            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI
//             DUAL;CN=Van Tung TRAN:mailto:vttran@domain.tld
//            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;CN=Twake
//              CALENDAR-DEV-2:mailto:twake-calendar-dev-2@domain.tld
//            SEQUENCE:1
//            BEGIN:VALARM
//            TRIGGER:-PT5M
//            ACTION:EMAIL
//            ATTENDEE:mailto:mailto:twake-calendar-dev-2@domain.tld
//            SUMMARY:Recurrence 4
//            DESCRIPTION:This is an automatic alarm sent by OpenPaas\\\\nThe event Recurre
//             nce 4 will start in 11 days\\\\nstart: Fri Aug 08 2025 13:00:00 GMT+0700 \\\\n
//             end: Fri Aug 08 2025 13:30:00 GMT+0700 \\\\nlocation:  \\\\nclass: PUBLIC \\\\n
//            END:VALARM
//            END:VEVENT
//            END:VCALENDAR
//            """;
//
//        Calendar calendar = CalendarUtil.parseIcs(calendarData);
//
//        Optional<Instant> alarmInstantOpt = testee.computeNextAlarmInstant(calendar);
//        assertTrue(alarmInstantOpt.isPresent(), "Alarm instant should be present");
//        Instant alarmInstant = alarmInstantOpt.get();
//        assertEquals(Instant.parse("2025-07-25T04:25:00Z"), alarmInstant);
//
//    }
}
