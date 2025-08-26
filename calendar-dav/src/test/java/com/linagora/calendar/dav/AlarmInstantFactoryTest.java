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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.storage.event.AlarmInstantFactory;
import com.linagora.calendar.storage.event.AlarmInstantFactory.AlarmInstant;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.property.RecurrenceId;

public class AlarmInstantFactoryTest {

    private AlarmInstantFactory testee(Instant clockFixedInstant) {
        return new AlarmInstantFactory.Default(Clock.fixed(clockFixedInstant, ZoneOffset.UTC));
    }

    private Optional<Instant> computeNextAlarmTime(String icsCalendar, Instant instantClock, Username attendee) {
        return testee(instantClock).computeNextAlarmInstant(CalendarUtil.parseIcs(icsCalendar), attendee)
            .map(AlarmInstant::alarmTime);
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
            ACTION:EMAIL
            TRIGGER:-PT10M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Optional<Instant> result = computeNextAlarmTime(ics, Instant.parse("2025-07-28T00:00:00Z"), Username.of("john@example.com"));

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
            ACTION:EMAIL
            TRIGGER:-PT10M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Optional<Instant> result = computeNextAlarmTime(ics, Instant.parse("2025-07-28T00:00:00Z"), Username.of("notfound@example.com"));

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
            ACTION:EMAIL
            TRIGGER:-PT10M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Optional<Instant> result = computeNextAlarmTime(ics, Instant.parse("2025-07-28T00:00:00Z"), Username.of("john@example.com"));
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
            ACTION:EMAIL
            ATTENDEE:mailto:jane1@example.com
            ATTENDEE:mailto:jane2@example.com
            TRIGGER:-PT15M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));

        Optional<AlarmInstant> result = testee.computeNextAlarmInstant(calendar, Username.of("jane@example.com"));

        // Expected: 2025-08-29T09:45:00Z (15 minutes before 10:00)
        assertThat(result)
            .describedAs("Alarm should be scheduled when event is in the future and attendee has accepted")
            .isPresent()
            .contains(new AlarmInstant(Instant.parse("2025-08-29T09:45:00Z"),
                Instant.parse("2025-08-29T10:00:00Z"),
                Optional.empty(),
                List.of(asMailAddress("jane1@example.com"),
                    asMailAddress("jane2@example.com"))));
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
            ACTION:EMAIL
            TRIGGER:-PT30M
            DESCRIPTION:Alarm 30 minutes before
            END:VALARM
            
            BEGIN:VALARM
            ACTION:EMAIL
            TRIGGER:-PT10M
            DESCRIPTION:Alarm 10 minutes before
            END:VALARM
            
            BEGIN:VALARM
            ACTION:EMAIL
            TRIGGER:-PT15M
            DESCRIPTION:Alarm 15 minutes before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Optional<Instant> result = computeNextAlarmTime(ics, Instant.parse("2025-07-28T00:00:00Z"), Username.of("alice@example.com"));

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
            ACTION:EMAIL
            TRIGGER:-PT1H
            DESCRIPTION:Alarm 1 hour before
            END:VALARM
            BEGIN:VALARM
            ACTION:EMAIL
            TRIGGER:-PT10M
            DESCRIPTION:Alarm 10 minutes before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        // fixedClock is 2025-08-29T09:30:00Z
        // → Alarm -1h = 09:00:00Z (in the past) → ignored
        // → Alarm -10m = 09:50:00Z (in the future) → picked
        Optional<Instant> result = computeNextAlarmTime(ics, Instant.parse("2025-08-29T09:30:00Z"), Username.of("alice@example.com"));

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
            ACTION:EMAIL
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
        Optional<Instant> result = computeNextAlarmTime(ics, Instant.parse("2025-08-29T09:30:00Z"), Username.of("bob@example.com"));

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

        Optional<Instant> result = computeNextAlarmTime(ics, Instant.parse("2025-07-28T00:00:00Z"), Username.of("john@example.com"));

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
            ACTION:EMAIL
            TRIGGER;RELATED=START:+PT15M
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Optional<Instant> result = computeNextAlarmTime(ics, Instant.parse("2025-07-28T00:00:00Z"), Username.of("john@example.com"));

        assertThat(result).contains(Instant.parse("2025-08-29T10:15:00Z"));
    }

    static Stream<Arguments> invalidAlarms() {
        return Stream.of(
            Arguments.of("No TRIGGER", """
                BEGIN:VALARM
                ACTION:EMAIL
                DESCRIPTION:Reminder
                END:VALARM""".trim()),
            Arguments.of("TRIGGER with non-duration", """
                BEGIN:VALARM
                TRIGGER:INVALID
                ACTION:EMAIL
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

    @Test
    void shouldComputeAlarmInstantWhenGoogleCalendarFormat() {
        String calendarContent = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            DTSTART:20250801T060000Z
            DTEND:20250801T063000Z
            DTSTAMP:20250730T024759Z
            ORGANIZER;CN=Tung Tran Van;SCHEDULE-STATUS=1.1:mailto:tungtv202@tmail.tld
            UID:33bg1ra5vmcbn5e6asvtmtbm4g@google.com
            ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED;RSVP=TRUE
             ;CN=vttran@domain.tld;X-NUM-GUESTS=0:mailto:vttran@domain.tld
            ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED;RSVP=TRUE
             ;CN=Tung Tran Van;X-NUM-GUESTS=0:mailto:tungtv202@tmail.tld
            X-GOOGLE-CONFERENCE:https://meet.google.com/gmg-zwji-oxc
            X-MICROSOFT-CDO-OWNERAPPTID:-450465403
            CREATED:20250730T024757Z
            DESCRIPTION:-::~:~::~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~
             :~:~:~:~:~:~:~:~::~:~::-\\nJoin with Google Meet: https://meet.google.com/g
             mg-zwji-oxc\\n\\nLearn more about Meet at: https://support.google.com/a/user
             s/answer/9282720\\n\\nPlease do not edit this section.\\n-::~:~::~:~:~:~:~:~:
             ~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~:~::~:~::-
            LAST-MODIFIED:20250730T024757Z
            LOCATION:
            SEQUENCE:0
            STATUS:CONFIRMED
            SUMMARY:Gmail 2
            TRANSP:OPAQUE
            BEGIN:VALARM
            ACTION:EMAIL
            DESCRIPTION:This is an event reminder
            TRIGGER:-P0DT0H30M0S
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(calendarContent);
        Username attendee = Username.of("vttran@domain.tld");
        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));

        assertThat(testee.computeNextAlarmInstant(calendar, attendee)
            .map(AlarmInstant::alarmTime))
            .isEqualTo(Optional.of(Instant.parse("2025-08-01T05:30:00Z")) /* 30 minutes before 06:00 UTC */);
    }

    @Test
    void shouldComputeAlarmInstantWhenOutlookCalendarFormat() {
        String calendarContent = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            ORGANIZER;CN=karen karen;SCHEDULE-STATUS=1.1:mailto:outlook_EDB053B6A879EC0
             2@outlook.com
            ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED;RSVP=TRUE;CN=tungtv@linagor
             a.ltd:mailto:tungtv@linagora.ltd
            DESCRIPTION;LANGUAGE=vi-VN:from outlook\\n\\n................................
             ..........................................................................
             ...............................\\nTham gia cuộc họp trực tuyến<http
             s://teams.live.com/meet/9392947982999?p=qcrvLRJ0ODK2a5uraK>\\n.............
             ..........................................................................
             ..................................................\\n
            UID:040000008200E00074C5B7101A82E00800000000ACB6EBCFFF00DC01000000000000000
             010000000FA8EAE1FBF89674F92C0CF70B16CEC80
            SUMMARY;LANGUAGE=vi-VN:Outlook 2
            DTSTART:20250801T043000Z
            DTEND:20250801T050000Z
            CLASS:PUBLIC
            PRIORITY:5
            DTSTAMP:20250730T031245Z
            TRANSP:OPAQUE
            STATUS:CONFIRMED
            SEQUENCE:0
            LOCATION;LANGUAGE=vi-VN:Cuộc họp trong Microsoft Teams
            X-MICROSOFT-CDO-APPT-SEQUENCE:0
            X-MICROSOFT-CDO-OWNERAPPTID:2123887532
            X-MICROSOFT-CDO-BUSYSTATUS:TENTATIVE
            X-MICROSOFT-CDO-INTENDEDSTATUS:BUSY
            X-MICROSOFT-CDO-ALLDAYEVENT:FALSE
            X-MICROSOFT-CDO-IMPORTANCE:1
            X-MICROSOFT-CDO-INSTTYPE:0
            X-MICROSOFT-ONLINEMEETINGINFORMATION:{"OnlineMeetingChannelId":null\\\\\\,"Onl
             ineMeetingProvider":4}
            X-MICROSOFT-SKYPETEAMSMEETINGURL:https://teams.live.com/meet/9392947982995?
             p=qcrvLRJ0ODK2a5uraK
            X-MICROSOFT-SCHEDULINGSERVICEUPDATEURL:https://api.scheduler.teams.microsof
             t.com/teamsforlife/9392947982995
            X-MICROSOFT-SKYPETEAMSPROPERTIES:{"cid":"19:meeting_ODUyYTcxZmMtMzk5Yi00MzU
             4LTk5ZGYtNjRhYTViMGY1OWVl@thread.v2"\\\\\\,"rid":0\\\\\\,"mid":0\\\\\\,"uid":null\\\\
             \\,"private":true\\\\\\,"type":0}
            X-MICROSOFT-DONOTFORWARDMEETING:FALSE
            X-MICROSOFT-DISALLOW-COUNTER:FALSE
            X-MICROSOFT-REQUESTEDATTENDANCEMODE:DEFAULT
            X-MICROSOFT-ISRESPONSEREQUESTED:TRUE
            X-MICROSOFT-LOCATIONDISPLAYNAME:Cuộc họp trong Microsoft Teams
            X-MICROSOFT-LOCATIONSOURCE:None
            X-MICROSOFT-LOCATIONS:[{"DisplayName":"Cuộc họp trong Microsoft Teams"\\
             \\\\,"LocationAnnotation":""\\\\\\,"LocationUri":""\\\\\\,"LocationStreet":""\\\\\\,"
             LocationCity":""\\\\\\,"LocationState":""\\\\\\,"LocationCountry":""\\\\\\,"Locatio
             nPostalCode":""\\\\\\,"LocationFullAddress":""}]
            BEGIN:VALARM
            DESCRIPTION:REMINDER
            TRIGGER;RELATED=START:-PT15M
            ACTION:EMAIL
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(calendarContent);
        Username attendee = Username.of("tungtv@linagora.ltd");
        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));

        assertThat(testee.computeNextAlarmInstant(calendar, attendee)
            .map(AlarmInstant::alarmTime))
            .isEqualTo(Optional.of(Instant.parse("2025-08-01T04:15:00Z")) /* 15 minutes before 04:30 UTC */);
    }

    @Test
    void shouldReturnEmptyWhenEventIsCancelled() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:789012
            DTSTART:20250829T100000Z
            DTEND:20250829T110000Z
            SUMMARY:Meeting
            ATTENDEE;CN=Jane Doe;PARTSTAT=ACCEPTED:mailto:jane@example.com
            STATUS:CANCELLED
            BEGIN:VALARM
            ACTION:EMAIL
            ATTENDEE:mailto:jane1@example.com
            ATTENDEE:mailto:jane2@example.com
            TRIGGER:-PT15M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        AlarmInstantFactory testee = testee(Instant.parse("2025-07-28T00:00:00Z"));

        Optional<AlarmInstant> result = testee.computeNextAlarmInstant(calendar, Username.of("jane@example.com"));
        assertThat(result)
            .isEmpty();
    }

    @Test
    void shouldPickLatestVersionWhenMultipleVEventsSameUIDWithoutRecurrence() {
        // two VEVENT with same UID, different SEQUENCE
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:same-uid-event
            DTSTART:20250829T100000Z
            SUMMARY:Old Version
            SEQUENCE:0
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
            BEGIN:VALARM
            ACTION:EMAIL
            TRIGGER:-PT15M
            END:VALARM
            END:VEVENT
            
            BEGIN:VEVENT
            UID:same-uid-event
            DTSTART:20250829T110000Z
            SUMMARY:New Version
            SEQUENCE:2
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
            BEGIN:VALARM
            ACTION:EMAIL
            TRIGGER:-PT10M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        Username attendee = Username.of("bob@example.com");

        AlarmInstantFactory testee = testee(Instant.parse("2025-08-28T00:00:00Z"));
        Optional<AlarmInstantFactory.AlarmInstant> result = testee.computeNextAlarmInstant(calendar, attendee);

        assertThat(result)
            .describedAs("Should pick the VEVENT with highest SEQUENCE as the latest version")
            .isPresent()
            .get()
            .extracting(AlarmInstantFactory.AlarmInstant::eventStartTime)
            .isEqualTo(Instant.parse("2025-08-29T11:00:00Z"));
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
                ACTION:EMAIL
                ATTENDEE:mailto:bob1@example.com
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """;

            Calendar calendar = CalendarUtil.parseIcs(calendarContent);
            Username attendee = Username.of("bob@example.com");
            MailAddress alarmRecipient = asMailAddress("bob1@example.com");
            // Define recurrence instances
            Instant start1 = Instant.parse("2025-08-29T10:00:00Z");
            Instant start2 = Instant.parse("2025-08-30T10:00:00Z");
            Instant start3 = Instant.parse("2025-08-31T10:00:00Z");

            AlarmInstant alarm1 = new AlarmInstant(
                start1.minus(15, ChronoUnit.MINUTES), start1,
                Optional.of(new RecurrenceId<>(start1)),
                List.of(alarmRecipient));

            AlarmInstant alarm2 = new AlarmInstant(
                start2.minus(15, ChronoUnit.MINUTES), start2,
                Optional.of(new RecurrenceId<>(start2)),
                List.of(alarmRecipient));

            AlarmInstant alarm3 = new AlarmInstant(
                start3.minus(15, ChronoUnit.MINUTES), start3,
                Optional.of(new RecurrenceId<>(start3)),
                List.of(alarmRecipient));

            Map<Instant, Optional<AlarmInstant>> testCases = Map.of(
                Instant.parse("2025-08-28T12:00:00Z"), Optional.of(alarm1), // Before all → returns alarm 1
                Instant.parse("2025-08-29T12:00:00Z"), Optional.of(alarm2), // After alarm 1 → returns alarm 2
                Instant.parse("2025-08-30T12:00:00Z"), Optional.of(alarm3), // After alarm 2 → returns alarm 3
                Instant.parse("2025-08-31T12:00:00Z"), Optional.empty()     /* After all alarms → empty */);


            for (Map.Entry<Instant, Optional<AlarmInstant>> entry : testCases.entrySet()) {
                Instant now = entry.getKey();
                Optional<AlarmInstant> expectedAlarm = entry.getValue();

                AlarmInstantFactory testee = testee(now);
                assertThat(testee.computeNextAlarmInstant(calendar, attendee))
                    .describedAs("Should return correct alarm for time %s", now)
                    .isEqualTo(expectedAlarm);
            }
        }

        @Test
        void shouldHandleBiWeeklyRecurrenceWithUntilInFuture() {
            String calendarContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:recurring-event
                DTSTART:20250806T090000Z
                DTEND:20250806T100000Z
                RRULE:FREQ=WEEKLY;INTERVAL=2;UNTIL=20251001T000000Z
                ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
                SUMMARY:Bi-Weekly Sync
                BEGIN:VALARM
                TRIGGER:-PT15M
                ACTION:EMAIL
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """;

            Calendar calendar = CalendarUtil.parseIcs(calendarContent);
            Username attendee = Username.of("bob@example.com");

            /*
                Occurrences (bi-weekly from 2025-08-06):
                - 2025-08-06T09:00Z → Alarm: 2025-08-06T08:45Z
                - 2025-08-20T09:00Z → Alarm: 2025-08-20T08:45Z
                - 2025-09-03T09:00Z → Alarm: 2025-09-03T08:45Z
                - 2025-09-17T09:00Z → Alarm: 2025-09-17T08:45Z
             */

            Map<Instant, Optional<Instant>> testCases = Map.of(
                Instant.parse("2025-08-01T00:00:00Z"), Optional.of(Instant.parse("2025-08-06T08:45:00Z")),
                Instant.parse("2025-08-06T10:00:00Z"), Optional.of(Instant.parse("2025-08-20T08:45:00Z")),
                Instant.parse("2025-08-21T00:00:00Z"), Optional.of(Instant.parse("2025-09-03T08:45:00Z")),
                Instant.parse("2025-09-17T09:00:01Z"), Optional.empty() /* after last alarm */);

            for (Map.Entry<Instant, Optional<Instant>> entry : testCases.entrySet()) {
                Instant now = entry.getKey();
                Optional<Instant> expectedAlarm = entry.getValue();

                AlarmInstantFactory testee = testee(now);

                assertThat(testee.computeNextAlarmInstant(calendar, attendee))
                    .map(AlarmInstant::alarmTime)
                    .describedAs("Should return correct alarm for fixed clock at %s", now)
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
                ACTION:EMAIL
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

                assertThat(testee.computeNextAlarmInstant(calendar, attendee)
                    .map(AlarmInstant::alarmTime))
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
                ACTION:EMAIL
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
                ACTION:EMAIL
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
            Username attendee = Username.of("alice@example.com");
            Optional<Instant> result = computeNextAlarmTime(calendarContent, fixedNow, attendee);

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
                ACTION:EMAIL
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
                ACTION:EMAIL
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """;

            // Fix clock between 1st and 2nd occurrence => next expected is 2nd
            Optional<Instant> result = computeNextAlarmTime(calendarContent, Instant.parse("2025-09-01T00:00:00Z"),
                Username.of("bob@example.com"));

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
                ACTION:EMAIL
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
                ACTION:EMAIL
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
                ACTION:EMAIL
                DESCRIPTION:Reminder
                END:VALARM
                END:VEVENT
                
                END:VCALENDAR
                """;

            // Fix clock between 1st and 2nd occurrence
            Optional<Instant> result = computeNextAlarmTime(calendarContent, Instant.parse("2025-09-01T00:00:00Z"), Username.of("bob@example.com"));

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
                ACTION:EMAIL
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

            // The second overridden occurrence has no VALARM -> expect no upcoming alarms
            assertThat(testee.computeNextAlarmInstant(calendar, attendee)).isEmpty();
        }

        @Test
        void shouldReturnEmptyWhenMasterAndOverrideCancelled() {
            String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:rec-456
                DTSTART:20250829T100000Z
                DTEND:20250829T110000Z
                SUMMARY:Daily Meeting
                RRULE:FREQ=DAILY;COUNT=2
                STATUS:CANCELLED
                ATTENDEE;CN=Jane Doe;PARTSTAT=ACCEPTED:mailto:jane@example.com
                BEGIN:VALARM
                ACTION:EMAIL
                ATTENDEE:mailto:jane@example.com
                TRIGGER:-PT15M
                END:VALARM
                END:VEVENT
                
                BEGIN:VEVENT
                UID:rec-456
                RECURRENCE-ID:20250830T100000Z
                DTSTART:20250830T100000Z
                DTEND:20250830T110000Z
                SUMMARY:Daily Meeting (Override also cancelled)
                STATUS:CANCELLED
                ATTENDEE;CN=Jane Doe;PARTSTAT=ACCEPTED:mailto:jane@example.com
                BEGIN:VALARM
                ACTION:EMAIL
                ATTENDEE:mailto:jane@example.com
                TRIGGER:-PT15M
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """;

            Calendar calendar = CalendarUtil.parseIcs(ics);
            AlarmInstantFactory testee = testee(Instant.parse("2025-08-29T00:00:00Z"));

            Optional<AlarmInstant> result = testee.computeNextAlarmInstant(calendar, Username.of("jane@example.com"));
            assertThat(result).isEmpty();
        }

        @Test
        void shouldReturnAlarmWhenMasterCancelledButOverrideActive() {
            String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:rec-123
                DTSTART:20250829T100000Z
                DTEND:20250829T110000Z
                SUMMARY:Daily Meeting
                RRULE:FREQ=DAILY;COUNT=2
                STATUS:CANCELLED
                ATTENDEE;CN=Jane Doe;PARTSTAT=ACCEPTED:mailto:jane@example.com
                BEGIN:VALARM
                ACTION:EMAIL
                ATTENDEE:mailto:jane@example.com
                TRIGGER:-PT15M
                END:VALARM
                END:VEVENT
                
                BEGIN:VEVENT
                UID:rec-123
                RECURRENCE-ID:20250830T100000Z
                DTSTART:20250830T100000Z
                DTEND:20250830T110000Z
                SUMMARY:Daily Meeting (Override active)
                ATTENDEE;CN=Jane Doe;PARTSTAT=ACCEPTED:mailto:jane@example.com
                BEGIN:VALARM
                ACTION:EMAIL
                ATTENDEE:mailto:jane@example.com
                TRIGGER:-PT15M
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """;

            Calendar calendar = CalendarUtil.parseIcs(ics);
            AlarmInstantFactory testee = testee(Instant.parse("2025-08-29T00:00:00Z"));

            Optional<AlarmInstant> result = testee.computeNextAlarmInstant(calendar, Username.of("jane@example.com"));
            assertThat(result)
                .isPresent()
                .get()
                .extracting(AlarmInstant::eventStartTime)
                .isEqualTo(Instant.parse("2025-08-30T10:00:00Z"));
        }
    }

    @Test
    void shouldComputeAlarmInstantForAllDayEvent() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:allday-event
            DTSTART;VALUE=DATE:20250829
            DTEND;VALUE=DATE:20250830
            SUMMARY:All Day Event
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
            BEGIN:VALARM
            ACTION:EMAIL
            TRIGGER:-PT15M
            DESCRIPTION:Reminder before all-day event
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        Username attendee = Username.of("bob@example.com");

        // All-day event starts at 2025-08-29T00:00:00Z (implicit)
        // Alarm = 2025-08-28T23:45:00Z
        Instant expectedAlarm = Instant.parse("2025-08-28T23:45:00Z");

        AlarmInstantFactory testee = testee(Instant.parse("2025-08-28T00:00:00Z"));

        assertThat(testee.computeNextAlarmInstant(calendar, attendee))
            .map(AlarmInstant::alarmTime)
            .describedAs("Should compute alarm 15 minutes before all-day event start (midnight UTC)")
            .contains(expectedAlarm);
    }

    @Test
    void shouldComputeAlarmInstantForRecurringAllDayEvent() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:recurring-allday-event
            DTSTART;VALUE=DATE:20250829
            DTEND;VALUE=DATE:20250830
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Recurring All Day Event
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
            BEGIN:VALARM
            ACTION:EMAIL
            TRIGGER:-PT15M
            DESCRIPTION:Reminder for all-day recurring event
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        Username attendee = Username.of("bob@example.com");

        Map<Instant, Optional<Instant>> testCases = Map.of(
            Instant.parse("2025-08-28T12:00:00Z"), Optional.of(Instant.parse("2025-08-28T23:45:00Z")),
            Instant.parse("2025-08-29T12:00:00Z"), Optional.of(Instant.parse("2025-08-29T23:45:00Z")),
            Instant.parse("2025-08-30T12:00:00Z"), Optional.of(Instant.parse("2025-08-30T23:45:00Z")),
            Instant.parse("2025-09-01T00:00:00Z"), Optional.empty());

        for (Map.Entry<Instant, Optional<Instant>> entry : testCases.entrySet()) {
            Instant now = entry.getKey();
            Optional<Instant> expectedAlarm = entry.getValue();

            AlarmInstantFactory testee = testee(now);

            assertThat(testee.computeNextAlarmInstant(calendar, attendee))
                .map(AlarmInstant::alarmTime)
                .describedAs("Should return correct alarm for all-day recurring event at %s", now)
                .isEqualTo(expectedAlarm);
        }
    }

    @Test
    void shouldRespectOverriddenAllDayRecurrenceAlarmTrigger() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:recurring-allday-event
            DTSTART;VALUE=DATE:20250829
            DTEND;VALUE=DATE:20250830
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Recurring All Day Event
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
            BEGIN:VALARM
            ACTION:EMAIL
            TRIGGER:-PT15M
            DESCRIPTION:Default Reminder
            END:VALARM
            END:VEVENT
            
            BEGIN:VEVENT
            UID:recurring-allday-event
            RECURRENCE-ID;VALUE=DATE:20250830
            DTSTART;VALUE=DATE:20250830
            DTEND;VALUE=DATE:20250831
            SUMMARY:Recurring All Day Event (Overridden Alarm)
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
            BEGIN:VALARM
            ACTION:EMAIL
            TRIGGER:-PT20M
            DESCRIPTION:Overridden Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        Username attendee = Username.of("bob@example.com");

        Map<Instant, Optional<Instant>> testCases = Map.of(
            // 1st occurrence: 2025-08-29 all-day → alarm 15m before midnight → 2025-08-28T23:45Z
            Instant.parse("2025-08-28T12:00:00Z"), Optional.of(Instant.parse("2025-08-28T23:45:00Z")),
            // 2nd occurrence overridden: 2025-08-30 all-day → alarm 20m before midnight → 2025-08-29T23:40Z
            Instant.parse("2025-08-29T12:00:00Z"), Optional.of(Instant.parse("2025-08-29T23:40:00Z")),
            // After all recurrences → no alarms
            Instant.parse("2025-08-31T00:00:00Z"), Optional.empty()
        );

        for (Map.Entry<Instant, Optional<Instant>> entry : testCases.entrySet()) {
            Instant now = entry.getKey();
            Optional<Instant> expectedAlarm = entry.getValue();

            AlarmInstantFactory testee = testee(now);

            assertThat(testee.computeNextAlarmInstant(calendar, attendee))
                .map(AlarmInstant::alarmTime)
                .describedAs("Should return correct alarm for overridden all-day recurrence at %s", now)
                .isEqualTo(expectedAlarm);
        }
    }


    @Test
    void shouldRespectOverriddenAllDayRecurrenceInstance() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:recurring-allday-event
            DTSTART;VALUE=DATE:20250829
            DTEND;VALUE=DATE:20250830
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Recurring All Day Event
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
            BEGIN:VALARM
            ACTION:EMAIL
            TRIGGER:-PT15M
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            
            BEGIN:VEVENT
            UID:recurring-allday-event
            RECURRENCE-ID;VALUE=DATE:20250830
            DTSTART;VALUE=DATE:20250901
            DTEND;VALUE=DATE:20250902
            SUMMARY:Rescheduled All Day Event
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.com
            BEGIN:VALARM
            ACTION:EMAIL
            TRIGGER:-PT15M
            DESCRIPTION:Rescheduled Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        Username attendee = Username.of("bob@example.com");

        Map<Instant, Optional<Instant>> testCases = Map.of(
            Instant.parse("2025-08-28T12:00:00Z"), Optional.of(Instant.parse("2025-08-28T23:45:00Z")),
            Instant.parse("2025-08-29T12:00:00Z"), Optional.of(Instant.parse("2025-08-30T23:45:00Z")),
            Instant.parse("2025-09-02T12:00:00Z"), Optional.empty());

        for (Map.Entry<Instant, Optional<Instant>> entry : testCases.entrySet()) {
            Instant now = entry.getKey();
            Optional<Instant> expectedAlarm = entry.getValue();

            AlarmInstantFactory testee = testee(now);

            assertThat(testee.computeNextAlarmInstant(calendar, attendee))
                .map(AlarmInstant::alarmTime)
                .describedAs("Should return correct alarm for overridden all-day recurrence at %s", now)
                .isEqualTo(expectedAlarm);
        }
    }

    private MailAddress asMailAddress(String email) {
        return Throwing.supplier(() -> new MailAddress(email)).get();
    }

}
