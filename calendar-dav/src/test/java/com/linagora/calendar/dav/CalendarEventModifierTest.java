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
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.dav.CalendarEventUpdatePatch.AttendeePartStatusUpdatePatch;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.property.Attendee;

public class CalendarEventModifierTest {
    static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX");
    static final String START_DATE_SAMPLE_VALUE = "20250320T150000Z";
    static final String END_DATE_SAMPLE_VALUE = "20250320T160000Z";
    static final ZonedDateTime START_DATE_SAMPLE = ZonedDateTime.parse(START_DATE_SAMPLE_VALUE, DATE_TIME_FORMATTER);
    static final ZonedDateTime END_DATE_SAMPLE = ZonedDateTime.parse(END_DATE_SAMPLE_VALUE, DATE_TIME_FORMATTER);

    public static final Calendar SAMPLE_CALENDAR = CalendarUtil.parseIcs(
        """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp//NONSGML Event//EN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:123456789@example.com
            DTSTAMP:20250318T120000Z
            DTSTART:%s
            DTEND:%s
            SUMMARY:Project Kickoff Meeting
            DESCRIPTION:Discuss project scope and deliverables.
            LOCATION:Conference Room A
            ORGANIZER:mailto:organizer@example.com
            ATTENDEE;CN=John Doe;RSVP=TRUE:mailto:johndoe@example.com
            ATTENDEE;CN=Jane Smith;RSVP=TRUE:mailto:janesmith@example.com
            SEQUENCE:2
            END:VEVENT
            END:VCALENDAR""".formatted(START_DATE_SAMPLE_VALUE, END_DATE_SAMPLE_VALUE).getBytes(StandardCharsets.UTF_8));

    @Nested
    class AttendeePartStatusPatchTest {

        @ParameterizedTest
        @ValueSource(strings = {"ACCEPTED", "DECLINED", "TENTATIVE"})
        void shouldUpdateAttendeePartStat(String partStatValue) {
            Calendar updatedCalendar = CalendarEventModifier.of(new AttendeePartStatusUpdatePatch("johndoe@example.com", new PartStat(partStatValue))).apply(SAMPLE_CALENDAR);

            assertThat(updatedCalendar.toString())
                .contains("ATTENDEE;CN=John Doe;RSVP=TRUE;PARTSTAT=" + partStatValue + ":mailto:johndoe@example.com");
        }

        @Test
        void shouldNotChangeOtherAttendeePartStat() {
            String attendee1 = "johndoe@example.com";
            String attendee2 = "janesmith@example.com";

            Calendar updated1 = CalendarEventModifier.of(new AttendeePartStatusUpdatePatch(attendee1, PartStat.ACCEPTED)).apply(SAMPLE_CALENDAR);

            Calendar updated2 = CalendarEventModifier.of(new AttendeePartStatusUpdatePatch(attendee2, PartStat.DECLINED)).apply(updated1);

            VEvent updatedVEvent = (VEvent) updated2.getComponent(VEvent.VEVENT).get();

            Optional<Attendee> found = updatedVEvent.getAttendees()
                .stream()
                .filter(a -> a.getCalAddress().toString().contains(attendee1))
                .findFirst();

            assertThat(found)
                .isPresent()
                .hasValueSatisfying(attendee -> assertThat(attendee.getParameter("PARTSTAT").get().getValue()).isEqualTo("ACCEPTED"));
        }

        @Test
        void shouldThrowIfPartStatUnchanged() {
            String attendee1 = "johndoe@example.com";

            Calendar updated1 = CalendarEventModifier.of(new AttendeePartStatusUpdatePatch(attendee1, PartStat.ACCEPTED)).apply(SAMPLE_CALENDAR);

            assertThatThrownBy(() -> CalendarEventModifier.of(new AttendeePartStatusUpdatePatch(attendee1, PartStat.ACCEPTED))
                .apply(updated1))
                .isInstanceOf(CalendarEventModifier.NoUpdateRequiredException.class);
        }

        @Test
        void shouldThrowIfAttendeeNotFound() {
            String nonExistingAttendee = UUID.randomUUID() + "@example.com";

            assertThatThrownBy(() ->
                CalendarEventModifier.of(new AttendeePartStatusUpdatePatch(nonExistingAttendee, PartStat.ACCEPTED)).apply(SAMPLE_CALENDAR))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldAllowChangingAttendeePartStat() {
            String attendee = "johndoe@example.com";

            Calendar updated1 = CalendarEventModifier.of(new AttendeePartStatusUpdatePatch(attendee, PartStat.ACCEPTED)).apply(SAMPLE_CALENDAR);

            Calendar updated2 = CalendarEventModifier.of(new AttendeePartStatusUpdatePatch(attendee, PartStat.DECLINED)).apply(updated1);

            VEvent updatedVEvent = (VEvent) updated2.getComponent(VEvent.VEVENT).get();

            Optional<Attendee> found = updatedVEvent.getAttendees()
                .stream()
                .filter(a -> a.getCalAddress().toString().contains(attendee))
                .findFirst();

            assertThat(found)
                .isPresent()
                .hasValueSatisfying(attendeeProp ->
                    assertThat(attendeeProp.getParameter("PARTSTAT").get().getValue()).isEqualTo("DECLINED"));
        }
    }

    @Test
    void shouldIncrementSequenceWhenChangingEvent() {
        String attendee = "johndoe@example.com";
        Calendar newCalendar = CalendarEventModifier.of(new AttendeePartStatusUpdatePatch(attendee, PartStat.ACCEPTED))
            .apply(SAMPLE_CALENDAR);

        VEvent parsedNewCalendar = (VEvent) newCalendar.getComponent(VEvent.VEVENT).get();

        assertThat(parsedNewCalendar.getSequence().getValue())
            .isEqualTo("3");
    }

    @Test
    void shouldAddSequenceDefaultWhenAbsentSEQUENCE() {
        Calendar absentSequenceCalendar = CalendarUtil.parseIcs((
            """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Example Corp//NONSGML Event//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:123456789@example.com
                DTSTAMP:20250318T120000Z
                DTSTART:%s
                DTEND:%s
                SUMMARY:Project Kickoff Meeting
                DESCRIPTION:Discuss project scope and deliverables.
                LOCATION:Conference Room A
                ORGANIZER:mailto:organizer@example.com
                ATTENDEE;CN=John Doe;RSVP=TRUE:mailto:johndoe@example.com
                ATTENDEE;CN=Jane Smith;RSVP=TRUE:mailto:janesmith@example.com
                END:VEVENT
                END:VCALENDAR
                """.formatted(START_DATE_SAMPLE_VALUE, END_DATE_SAMPLE_VALUE)).getBytes(StandardCharsets.UTF_8));

        String attendee = "johndoe@example.com";
        Calendar newCalendar = CalendarEventModifier.of(new AttendeePartStatusUpdatePatch(attendee, PartStat.ACCEPTED))
            .apply(absentSequenceCalendar);

        VEvent parsedNewCalendar = (VEvent) newCalendar.getComponent(VEvent.VEVENT).get();

        assertThat(parsedNewCalendar.getSequence().getValue())
            .isEqualTo("1");
    }

    @Test
    void shouldKeepOtherPropertiesWhenChangingEvent() {
        String attendee = "johndoe@example.com";
        Calendar newCalendar = CalendarEventModifier.of(new AttendeePartStatusUpdatePatch(attendee, PartStat.ACCEPTED))
            .apply(SAMPLE_CALENDAR);

        Function<String, String> filterFunction = input -> Arrays.stream(input.split("\r?\n"))
            .filter(line -> !line.startsWith("DTSTAMP:")
                && !line.startsWith("SEQUENCE:")
                && !line.startsWith("ATTENDEE;"))
            .collect(Collectors.joining("\n")).trim();

        assertThat(filterFunction.apply(SAMPLE_CALENDAR.toString()))
            .isEqualToNormalizingNewlines(filterFunction.apply(newCalendar.toString()));

        assertThat(newCalendar.toString())
            .contains("""
                    ATTENDEE;CN=John Doe;RSVP=TRUE;PARTSTAT=ACCEPTED:mailto:johndoe@example.com""".trim(),
                """
                    ATTENDEE;CN=Jane Smith;RSVP=TRUE:mailto:janesmith@example.com""".trim());
    }

    @Test
    void shouldThrowWhenNoVEventPresent() {
        Calendar noVEventCalendar = CalendarUtil.parseIcs("""
                BEGIN:VCALENDAR
                VERSION:2.0
                END:VCALENDAR
                """.stripIndent().getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> CalendarEventModifier
            .of(new AttendeePartStatusUpdatePatch("johndoe@example.com", PartStat.ACCEPTED))
            .apply(noVEventCalendar))
            .isInstanceOf(IllegalStateException.class);
    }
}
