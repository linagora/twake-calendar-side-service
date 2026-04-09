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
 
package com.linagora.calendar.storage.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.api.CalendarUtil;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;

class EventParseUtilsTest {

    @Test
    void getEndTimeShouldReturnDtEndWhenPresent() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-1
            DTSTART:20250911T100000Z
            DTEND:20250911T120000Z
            SUMMARY:Meeting with DTEND
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        VEvent event = (VEvent) calendar.getComponent(Component.VEVENT).get();

        Optional<ZonedDateTime> result = EventParseUtils.getEndTime(event);

        assertThat(result.get())
            .isEqualTo(ZonedDateTime.of(2025, 9, 11, 12, 0, 0, 0, ZoneId.of("UTC")));
    }

    @Test
    void getEndTimeShouldUseDurationWhenNoDtEnd() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-2
            DTSTART:20250911T100000Z
            DURATION:PT3H
            SUMMARY:Meeting with DURATION
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        VEvent event = (VEvent) calendar.getComponent(Component.VEVENT).get();

        Optional<ZonedDateTime> result = EventParseUtils.getEndTime(event);

        assertThat(result.get())
            .isEqualTo(ZonedDateTime.of(2025, 9, 11, 13, 0, 0, 0, ZoneId.of("UTC")));
    }

    @Test
    void getEndTimeShouldRespectTZID() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-5
            DTSTART;TZID=Europe/Paris:20250911T100000
            DTEND;TZID=Europe/Paris:20250911T120000
            SUMMARY:Meeting with TZID
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        VEvent event = (VEvent) calendar.getComponent(Component.VEVENT).get();

        Optional<ZonedDateTime> result = EventParseUtils.getEndTime(event);

        assertThat(result).isPresent();
        assertThat(result.get())
            .isEqualTo(ZonedDateTime.of(2025, 9, 11, 12, 0, 0, 0, ZoneId.of("Europe/Paris")));
    }

    @Test
    void getEndTimeShouldUseDurationWithTZID() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-6
            DTSTART;TZID=Europe/Paris:20250911T100000
            DURATION:PT2H30M
            SUMMARY:Meeting with TZID and DURATION
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        VEvent event = (VEvent) calendar.getComponent(Component.VEVENT).get();

        Optional<ZonedDateTime> result = EventParseUtils.getEndTime(event);

        assertThat(result).isPresent();
        assertThat(result.get())
            .isEqualTo(ZonedDateTime.of(2025, 9, 11, 12, 30, 0, 0, ZoneId.of("Europe/Paris")));
    }

    @Test
    void getEndTimeShouldHandleAllDayEvent() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-4
            DTSTART;VALUE=DATE:20250911
            DTEND;VALUE=DATE:20250912
            SUMMARY:All day event
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        VEvent event = (VEvent) calendar.getComponent(Component.VEVENT).get();

        Optional<ZonedDateTime> result = EventParseUtils.getEndTime(event);

        assertThat(result).isPresent();
        assertThat(result.get())
            .isEqualTo(ZonedDateTime.of(2025, 9, 12, 0, 0, 0, 0, ZoneId.of("UTC")));
    }

    @Test
    void getEndTimeShouldHandleAllDayEventWithDuration() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-7
            DTSTART;VALUE=DATE:20250911
            DURATION:P2D
            SUMMARY:All-day event with duration
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        VEvent event = (VEvent) calendar.getComponent(Component.VEVENT).get();

        Optional<ZonedDateTime> result = EventParseUtils.getEndTime(event);

        assertThat(result).isPresent();
        assertThat(result.get())
            .isEqualTo(ZonedDateTime.of(2025, 9, 13, 0, 0, 0, 0, ZoneId.of("UTC")));
    }

    @Test
    void getOrganizerShouldReturnPerson() throws AddressException {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-1
            DTSTART:20250911T100000Z
            DTEND:20250911T120000Z
            SUMMARY:Meeting with DTEND
            ORGANIZER;CN=Test Organizer:mailto:organizer@abc.com
            ATTENDEE;CN=Test Attendee:mailto:attendee@abc.com
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        VEvent event = (VEvent) calendar.getComponent(Component.VEVENT).get();

        assertThat(EventParseUtils.getOrganizer(event)).isEqualTo(new EventFields.Person("Test Organizer", new MailAddress("organizer@abc.com")));
    }

    @Test
    void getOrganizerShouldReturnPersonWhenMailToStringContainsPlusSign() throws AddressException {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-1
            DTSTART:20250911T100000Z
            DTEND:20250911T120000Z
            SUMMARY:Meeting with DTEND
            ORGANIZER;CN=Test Organizer:mailto:organizer+calendar@abc.com
            ATTENDEE;CN=Test Attendee:mailto:attendee@abc.com
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        VEvent event = (VEvent) calendar.getComponent(Component.VEVENT).get();

        assertThat(EventParseUtils.getOrganizer(event)).isEqualTo(new EventFields.Person("Test Organizer", new MailAddress("organizer+calendar@abc.com")));
    }

    @Test
    void getOrganizerShouldReturnCorrectPersonWhenMailToStringHasComplexFormat() throws AddressException {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-1
            DTSTART:20250911T100000Z
            DTEND:20250911T120000Z
            SUMMARY:Meeting with DTEND
            ORGANIZER;CN=Test Organizer:mailto:Test%20Organizer%20%3Corganizer@abc.com%3E
            ATTENDEE;CN=Test Attendee:mailto:attendee@abc.com
            END:VEVENT
            END:VCALENDAR
            """;

        Calendar calendar = CalendarUtil.parseIcs(ics);
        VEvent event = (VEvent) calendar.getComponent(Component.VEVENT).get();

        assertThat(EventParseUtils.getOrganizer(event)).isEqualTo(new EventFields.Person("Test Organizer", new MailAddress("organizer@abc.com")));
    }

    @Nested
    class CreateInstanceVEvent {

        // shared ICS: weekly on Friday, TZID=UTC
        private static final String WEEKLY_UTC_ICS = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-1
            DTSTART:20250411T090000Z
            DTEND:20250411T100000Z
            RRULE:FREQ=WEEKLY;BYDAY=FR
            SUMMARY:Weekly Meeting
            DESCRIPTION:Team sync
            LOCATION:Conference Room
            ORGANIZER:mailto:org@example.com
            ATTENDEE:mailto:att@example.com
            EXDATE:20250418T090000Z
            END:VEVENT
            END:VCALENDAR
            """;

        @Test
        void shouldRemoveRruleAndExdate() {
            Calendar calendar = CalendarUtil.parseIcs(WEEKLY_UTC_ICS);
            VEvent master = (VEvent) calendar.getComponent(Component.VEVENT).get();

            VEvent instance = EventParseUtils.createInstanceVEvent(master,
                ZonedDateTime.parse("2025-04-11T09:00:00Z"));

            assertSoftly(softly -> {
                softly.assertThat(instance.getProperty(Property.RRULE)).isEmpty();
                softly.assertThat(instance.getProperty(Property.EXDATE)).isEmpty();
            });
        }

        @Test
        void shouldSetCorrectDtStartForOccurrence() {
            Calendar calendar = CalendarUtil.parseIcs(WEEKLY_UTC_ICS);
            VEvent master = (VEvent) calendar.getComponent(Component.VEVENT).get();

            VEvent instance1 = EventParseUtils.createInstanceVEvent(master,
                ZonedDateTime.parse("2025-04-11T09:00:00Z"));
            VEvent instance2 = EventParseUtils.createInstanceVEvent(master,
                ZonedDateTime.parse("2025-04-25T09:00:00Z"));

            assertSoftly(softly -> {
                softly.assertThat(EventParseUtils.getStartTime(instance1))
                    .isEqualTo(ZonedDateTime.parse("2025-04-11T09:00:00Z"));
                softly.assertThat(EventParseUtils.getStartTime(instance2))
                    .isEqualTo(ZonedDateTime.parse("2025-04-25T09:00:00Z"));
            });
        }

        @Test
        void shouldPreserveOtherProperties() {
            Calendar calendar = CalendarUtil.parseIcs(WEEKLY_UTC_ICS);
            VEvent master = (VEvent) calendar.getComponent(Component.VEVENT).get();

            VEvent instance = EventParseUtils.createInstanceVEvent(master,
                ZonedDateTime.parse("2025-04-11T09:00:00Z"));

            assertSoftly(softly -> {
                softly.assertThat(instance.getUid())
                    .isPresent()
                    .satisfies(uid -> assertThat(uid.get().getValue()).isEqualTo("event-1"));
                softly.assertThat(instance.getSummary().getValue()).isEqualTo("Weekly Meeting");
                softly.assertThat(instance.getDescription().getValue()).isEqualTo("Team sync");
                softly.assertThat(instance.getLocation().getValue()).isEqualTo("Conference Room");
                softly.assertThat(instance.getProperty(Property.ORGANIZER)).isPresent();
                softly.assertThat(instance.getProperty(Property.ATTENDEE)).isPresent();
            });
        }

        @Test
        void shouldThrowWhenRecurrenceDateNotInRrule() {
            Calendar calendar = CalendarUtil.parseIcs(WEEKLY_UTC_ICS);
            VEvent master = (VEvent) calendar.getComponent(Component.VEVENT).get();
            // April 12 is a Saturday, not in RRULE:FREQ=WEEKLY;BYDAY=FR
            ZonedDateTime notInRrule = ZonedDateTime.parse("2025-04-12T09:00:00Z");

            assertThatThrownBy(() -> EventParseUtils.createInstanceVEvent(master, notInRrule))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldHandleUtcDateTime() {
            String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:event-1
                DTSTART:20250411T090000Z
                DTEND:20250411T100000Z
                RRULE:FREQ=WEEKLY;BYDAY=FR
                SUMMARY:Weekly Meeting
                END:VEVENT
                END:VCALENDAR
                """;
            Calendar calendar = CalendarUtil.parseIcs(ics);
            VEvent master = (VEvent) calendar.getComponent(Component.VEVENT).get();

            VEvent instance = EventParseUtils.createInstanceVEvent(master,
                ZonedDateTime.parse("2025-04-11T09:00:00Z"));

            assertThat(instance.toString())
                .contains("RECURRENCE-ID:20250411T090000Z",
                    "DTSTART:20250411T090000Z",
                    "DTEND:20250411T100000Z");
        }

        @Test
        void shouldHandleAllDayEvent() {
            String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:event-1
                DTSTART;VALUE=DATE:20250411
                DTEND;VALUE=DATE:20250412
                RRULE:FREQ=WEEKLY;BYDAY=FR
                SUMMARY:Weekly All-day Meeting
                END:VEVENT
                END:VCALENDAR
                """;
            Calendar calendar = CalendarUtil.parseIcs(ics);
            VEvent master = (VEvent) calendar.getComponent(Component.VEVENT).get();

            VEvent instance = EventParseUtils.createInstanceVEvent(master, LocalDate.of(2025, 4, 11));

            assertThat(instance.toString())
                .contains("RECURRENCE-ID;VALUE=DATE:20250411",
                    "DTSTART;VALUE=DATE:20250411",
                    "DTEND;VALUE=DATE:20250412");
        }

        @Test
        void shouldHandleTimezoneDateTime() {
            String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:event-1
                DTSTART;TZID=Asia/Ho_Chi_Minh:20250411T090000
                DTEND;TZID=Asia/Ho_Chi_Minh:20250411T100000
                RRULE:FREQ=WEEKLY;BYDAY=FR
                SUMMARY:Weekly Meeting
                END:VEVENT
                END:VCALENDAR
                """;
            Calendar calendar = CalendarUtil.parseIcs(ics);
            VEvent master = (VEvent) calendar.getComponent(Component.VEVENT).get();

            VEvent instance = EventParseUtils.createInstanceVEvent(master,
                ZonedDateTime.parse("2025-04-11T09:00:00+07:00[Asia/Ho_Chi_Minh]"));

            assertThat(instance.toString())
                .contains("RECURRENCE-ID;TZID=Asia/Ho_Chi_Minh:20250411T090000",
                    "DTSTART;TZID=Asia/Ho_Chi_Minh:20250411T090000",
                    "DTEND;TZID=Asia/Ho_Chi_Minh:20250411T100000");
        }
    }
}
