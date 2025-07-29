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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.james.core.Username;
import org.junit.jupiter.api.Nested;
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
    void shouldReturnEmptyWhenAttendeeHasNotAccepted() {
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
        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));
        Optional<Instant> result = testee.computeNextAlarmInstant(calendar, Username.of("john@example.com"));

        assertThat(result)
            .describedAs("Alarm should not be scheduled when attendee has not accepted the invitation")
            .isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenAttendeeNotFoundInEvent() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:123456
            DTSTART:20250829T100000Z
            SUMMARY:Test Event
            ATTENDEE;CN=Jane Doe;PARTSTAT=ACCEPTED:mailto:jane@example.com
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT10M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));
        Optional<Instant> result = testee.computeNextAlarmInstant(calendar, Username.of("notfound@example.com"));

        assertThat(result)
            .describedAs("Alarm should not be scheduled when the given attendee is not present in the event")
            .isEmpty();
    }


    @Test
    void shouldReturnEmptyWhenEventAlreadyOccurred() {
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
        Optional<Instant> result = testee.computeNextAlarmInstant(calendar, Username.of("john@example.com"));

        assertThat(result)
            .describedAs("Alarm should not be scheduled for past event")
            .isEmpty();
    }

    @Test
    void shouldReturnAlarmInstantWhenAttendeeHasAcceptedAndEventIsInFuture() {
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
        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));

        Optional<Instant> result = testee.computeNextAlarmInstant(calendar, Username.of("jane@example.com"));

        // Expected: 2025-08-29T09:45:00Z (15 minutes before 10:00)
        assertThat(result)
            .describedAs("Alarm should be scheduled when event is in the future and attendee has accepted")
            .isPresent()
            .contains(Instant.parse("2025-08-29T09:45:00Z"));
    }

    @Test
    void shouldPickEarliestValidAlarmAmongMultiple() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:multi-alarm-event
            DTSTART:20250829T100000Z
            SUMMARY:Team Meeting
            ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.com
            
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:Alarm 30 minutes before
            END:VALARM
            
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT10M
            DESCRIPTION:Alarm 10 minutes before
            END:VALARM
            
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Alarm 15 minutes before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));

        Optional<Instant> result = testee.computeNextAlarmInstant(calendar, Username.of("alice@example.com"));

        // Expected: 2025-08-29T09:30:00Z (30 minutes before 10:00)
        assertThat(result)
            .describedAs("Should return alarm time from the earliest valid VALARM")
            .isPresent()
            .contains(Instant.parse("2025-08-29T09:30:00Z"));
    }

    @Test
    void shouldIgnorePastAlarmsAndPickValidFutureOne() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-with-mixed-alarms
            DTSTART:20250829T100000Z
            SUMMARY:Project Review
            ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.com
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:Alarm 1 hour before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT10M
            DESCRIPTION:Alarm 10 minutes before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        // fixedClock is 2025-08-29T09:30:00Z
        // → Alarm -1h = 09:00:00Z (in the past) → ignored
        // → Alarm -10m = 09:50:00Z (in the future) → picked

        Calendar calendar = CalendarUtil.parseIcs(ics);
        AlarmInstantFactory testee = testee(Instant.parse("2025-08-29T09:30:00Z"));

        Optional<Instant> result = testee.computeNextAlarmInstant(calendar, Username.of("alice@example.com"));

        assertThat(result)
            .describedAs("Only future alarm should be considered")
            .contains(Instant.parse("2025-08-29T09:50:00Z"));
    }

    @Test
    void shouldReturnEmptyWhenAllAlarmsAreInThePastEvenIfEventIsFuture() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-all-alarms-past
            DTSTART:20250829T100000Z
            SUMMARY:Design Review
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT2H
            DESCRIPTION:Alarm 2 hours before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        // fixedClock is 2025-08-29T09:30:00Z
        // → Alarm = 08:00:00Z → already passed
        // → Event = 10:00:00Z → still in the future
        // → But no alarms are valid → result empty

        Calendar calendar = CalendarUtil.parseIcs(ics);
        AlarmInstantFactory testee = testee(Instant.parse("2025-08-29T09:30:00Z"));

        Optional<Instant> result = testee.computeNextAlarmInstant(calendar, Username.of("bob@example.com"));

        assertThat(result)
            .describedAs("Should return empty when all alarms are already in the past")
            .isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenNoVALARMPresent() {
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
        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));

        Optional<Instant> result = testee.computeNextAlarmInstant(calendar, Username.of("john@example.com"));

        assertThat(result)
            .as("Alarm should not be scheduled when no VALARM is present")
            .isEmpty();
    }

    @Test
    void shouldComputeAlarmInstantBasedOnTriggerRelativeToStart() {
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
        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));
        Optional<Instant> result = testee.computeNextAlarmInstant(calendar, Username.of("john@example.com"));

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

        assertThat(testee.computeNextAlarmInstant(calendar, attendee)).isEmpty();
    }

    @Nested
    class RecurrenceEventTest {
        @Test
        void shouldReturnCorrectAlarmInstantForEachOccurrenceInRRule() {
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

            // Occurrence 1: 2025-08-29T10:00Z → Alarm: 2025-08-29T09:45Z
            // Occurrence 2: 2025-08-30T10:00Z → Alarm: 2025-08-30T09:45Z
            // Occurrence 3: 2025-08-31T10:00Z → Alarm: 2025-08-31T09:45Z

            Map<Instant, Optional<Instant>> testCases = Map.of(
                Instant.parse("2025-08-28T12:00:00Z"), Optional.of(Instant.parse("2025-08-29T09:45:00Z")), // before all → alarm 1
                Instant.parse("2025-08-29T12:00:00Z"), Optional.of(Instant.parse("2025-08-30T09:45:00Z")), // after alarm 1 → alarm 2
                Instant.parse("2025-08-30T12:00:00Z"), Optional.of(Instant.parse("2025-08-31T09:45:00Z")), // after alarm 2 → alarm 3
                Instant.parse("2025-08-31T12:00:00Z"), Optional.empty() /* after al → empty */);

            for (Map.Entry<Instant, Optional<Instant>> entry : testCases.entrySet()) {
                Instant now = entry.getKey();
                Optional<Instant> expectedAlarm = entry.getValue();

                AlarmInstantFactory testee = testee(now);
                Optional<Instant> result = testee.computeNextAlarmInstant(calendar, attendee);

                assertThat(result)
                    .describedAs("Should return correct alarm for time %s", now)
                    .isEqualTo(expectedAlarm);
            }
        }

        @Test
        void shouldRespectEXDATEInRecurringEvents() {
            String calendarContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:recurring-event
                DTSTART:20250829T100000Z
                DTEND:20250829T110000Z
                RRULE:FREQ=DAILY;COUNT=4
                EXDATE:20250830T100000Z,20250831T100000Z
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

            // RRULE: 2025-08-29, 2025-08-30, 2025-08-31, 2025-09-01
            // EXDATE: 2025-08-30, 2025-08-31
            // Remaining alarms:
            // - 2025-08-29T10:00Z → alarm: 09:45Z
            // - 2025-09-01T10:00Z → alarm: 09:45Z

            Map<Instant, Optional<Instant>> testCases = Map.of(
                Instant.parse("2025-08-28T12:00:00Z"), Optional.of(Instant.parse("2025-08-29T09:45:00Z")), // before all
                Instant.parse("2025-08-29T12:00:00Z"), Optional.of(Instant.parse("2025-09-01T09:45:00Z")), // after 1st, skip 2 & 3
                Instant.parse("2025-09-01T12:00:00Z"), Optional.empty() /* all passed */);

            for (Map.Entry<Instant, Optional<Instant>> entry : testCases.entrySet()) {
                Instant now = entry.getKey();
                Optional<Instant> expectedAlarm = entry.getValue();

                AlarmInstantFactory testee = testee(now);
                Optional<Instant> result = testee.computeNextAlarmInstant(calendar, attendee);

                assertThat(result)
                    .describedAs("Should return correct alarm considering multiple EXDATEs for time %s", now)
                    .isEqualTo(expectedAlarm);
            }
        }

        @Test
        void shouldRespectOverriddenRecurrenceInstance() {
            String calendarContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:recurring-event
                DTSTART:20250801T100000Z
                DTEND:20250801T110000Z
                RRULE:FREQ=WEEKLY;COUNT=3
                ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.com
                SUMMARY:Weekly Meeting
                BEGIN:VALARM
                TRIGGER:-PT15M
                ACTION:DISPLAY
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                
                BEGIN:VEVENT
                UID:recurring-event
                RECURRENCE-ID:20250815T100000Z
                DTSTART:20250816T140000Z
                DTEND:20250816T150000Z
                ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.com
                SUMMARY:Rescheduled Weekly Meeting
                BEGIN:VALARM
                TRIGGER:-PT15M
                ACTION:DISPLAY
                DESCRIPTION:Rescheduled Reminder
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """;

            /*
             * Original occurrences:
             * - 2025-08-01T10:00:00Z → alarm at 09:45:00Z
             * - 2025-08-08T10:00:00Z → alarm at 09:45:00Z
             * - 2025-08-15T10:00:00Z → replaced by 2025-08-16T14:00:00Z → alarm at 13:45:00Z
             */

            Instant fixedNow = Instant.parse("2025-08-08T10:00:01Z"); // after second alarm, before third

            Calendar calendar = CalendarUtil.parseIcs(calendarContent);
            Username attendee = Username.of("alice@example.com");

            AlarmInstantFactory testee = testee(fixedNow);

            Optional<Instant> result = testee.computeNextAlarmInstant(calendar, attendee);

            // Expect alarm of overridden occurrence → 2025-08-16T13:45:00Z
            assertThat(result).contains(Instant.parse("2025-08-16T13:45:00Z"));
        }

        @Test
        void shouldReturnAlarmOfThirdOccurrenceWhenSecondOverriddenAndDeclined() {
            String calendarContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:recurring-event
                DTSTART:20250829T100000Z
                DTEND:20250829T110000Z
                RRULE:FREQ=WEEKLY;COUNT=3
                ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
                SUMMARY:Weekly Meeting
                BEGIN:VALARM
                TRIGGER:-PT15M
                ACTION:DISPLAY
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                
                BEGIN:VEVENT
                UID:recurring-event
                RECURRENCE-ID:20250905T100000Z
                DTSTART:20250905T100000Z
                DTEND:20250905T110000Z
                ATTENDEE;CN=Bob;PARTSTAT=DECLINED:mailto:bob@example.com
                SUMMARY:Weekly Meeting (Override)
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

            // Fix clock between 1st and 2nd occurrence => next expected is 2nd
            AlarmInstantFactory testee = testee(Instant.parse("2025-09-01T00:00:00Z"));

            Optional<Instant> result = testee.computeNextAlarmInstant(calendar, attendee);

            // 2nd is DECLINED, so should skip to 3rd: 2025-09-12T10:00:00Z - 15m
            assertThat(result).contains(Instant.parse("2025-09-12T09:45:00Z"));
        }

        @Test
        void shouldSkipDeclinedAndUseOverriddenNextOccurrence() {
            String calendarContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                
                BEGIN:VEVENT
                UID:recurring-event
                DTSTART:20250829T100000Z
                DTEND:20250829T110000Z
                RRULE:FREQ=WEEKLY;COUNT=3
                ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
                SUMMARY:Weekly Sync
                BEGIN:VALARM
                TRIGGER:-PT15M
                ACTION:DISPLAY
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                
                BEGIN:VEVENT
                UID:recurring-event
                RECURRENCE-ID:20250905T100000Z
                DTSTART:20250905T100000Z
                DTEND:20250905T110000Z
                ATTENDEE;CN=Bob;PARTSTAT=DECLINED:mailto:bob@example.com
                SUMMARY:Weekly Sync (Declined)
                BEGIN:VALARM
                TRIGGER:-PT15M
                ACTION:DISPLAY
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                
                BEGIN:VEVENT
                UID:recurring-event
                RECURRENCE-ID:20250912T100000Z
                DTSTART:20250912T120000Z
                DTEND:20250912T130000Z
                ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
                SUMMARY:Weekly Sync (Time Changed)
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

            // Fix clock between 1st and 2nd occurrence
            AlarmInstantFactory testee = testee(Instant.parse("2025-09-01T00:00:00Z"));

            Optional<Instant> result = testee.computeNextAlarmInstant(calendar, attendee);

            // 3rd occurrence is overridden
            assertThat(result).contains(Instant.parse("2025-09-12T11:45:00Z"));
        }

        @Test
        void shouldSkipOverriddenOccurrenceWithoutAlarmAndReturnNothingWhenNoFurtherAlarmExists() {
            String calendarContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                
                BEGIN:VEVENT
                UID:recurring-event
                DTSTART:20250829T100000Z
                DTEND:20250829T110000Z
                RRULE:FREQ=WEEKLY;COUNT=2
                ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
                SUMMARY:Weekly Sync
                BEGIN:VALARM
                TRIGGER:-PT15M
                ACTION:DISPLAY
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                
                BEGIN:VEVENT
                UID:recurring-event
                RECURRENCE-ID:20250905T100000Z
                DTSTART:20250905T100000Z
                DTEND:20250905T110000Z
                ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
                SUMMARY:Weekly Sync (No Alarm)
                END:VEVENT
                
                END:VCALENDAR
                """;

            Calendar calendar = CalendarUtil.parseIcs(calendarContent);
            Username attendee = Username.of("bob@example.com");

            // Fix clock after first occurrence, but before second (which is overridden and has no alarm)
            AlarmInstantFactory testee = testee(Instant.parse("2025-09-01T00:00:00Z"));

            Optional<Instant> result = testee.computeNextAlarmInstant(calendar, attendee);

            // The second overridden occurrence has no VALARM -> expect no upcoming alarms
            assertThat(result).isEmpty();
        }
    }

}
