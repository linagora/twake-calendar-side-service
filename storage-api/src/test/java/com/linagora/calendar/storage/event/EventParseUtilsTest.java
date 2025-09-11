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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.linagora.calendar.api.CalendarUtil;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
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
}
