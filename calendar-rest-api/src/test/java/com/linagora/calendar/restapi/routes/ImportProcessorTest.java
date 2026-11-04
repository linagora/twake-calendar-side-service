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

package com.linagora.calendar.restapi.routes;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.linagora.calendar.dav.importer.EventToImport;

class ImportProcessorTest {

    private static Map<String, Object> failedItemValues(String ics) {
        EventToImport event = EventToImport.parse(ics.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)).getFirst();
        return ImportProcessor.ImportICSToDavHandler.failedItemFromEvent(event).keyValues();
    }

    @Test
    void failedItemShouldHoldReadableUtcDates() {
        Map<String, Object> values = failedItemValues("""
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:uid-1
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Broken event
            END:VEVENT
            END:VCALENDAR
            """);

        assertThat(values)
            .containsEntry("uid", "uid-1")
            .containsEntry("summary", "Broken event")
            .containsEntry("start", "2025-01-02 12:00 UTC")
            .containsEntry("end", "2025-01-02 13:00 UTC");
    }

    @Test
    void failedItemShouldDisplayTheTimeZoneOfZonedDates() {
        Map<String, Object> values = failedItemValues("""
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:uid-1
            DTSTAMP:20250101T100000Z
            DTSTART;TZID=Europe/Paris:20250102T120000
            DTEND;TZID=Europe/Paris:20250102T130000
            SUMMARY:Zoned event
            END:VEVENT
            END:VCALENDAR
            """);

        assertThat(values)
            .containsEntry("start", "2025-01-02 12:00 Europe/Paris")
            .containsEntry("end", "2025-01-02 13:00 Europe/Paris");
    }

    @Test
    void failedItemShouldDisplayFloatingDatesWithoutTimeZone() {
        Map<String, Object> values = failedItemValues("""
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:uid-1
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000
            DTEND:20250102T130000
            SUMMARY:Floating event
            END:VEVENT
            END:VCALENDAR
            """);

        assertThat(values)
            .containsEntry("start", "2025-01-02 12:00")
            .containsEntry("end", "2025-01-02 13:00");
    }

    @Test
    void failedItemShouldDisplayTheDateAloneForAllDayEvents() {
        Map<String, Object> values = failedItemValues("""
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:uid-1
            DTSTAMP:20250101T100000Z
            DTSTART;VALUE=DATE:20250102
            DTEND;VALUE=DATE:20250103
            SUMMARY:All day event
            END:VEVENT
            END:VCALENDAR
            """);

        assertThat(values)
            .containsEntry("start", "2025-01-02")
            .containsEntry("end", "2025-01-03");
    }

    @Test
    void failedItemShouldDeriveTheEndFromTheDuration() {
        Map<String, Object> values = failedItemValues("""
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:uid-1
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DURATION:PT30M
            SUMMARY:Event with duration
            END:VEVENT
            END:VCALENDAR
            """);

        assertThat(values)
            .containsEntry("start", "2025-01-02 12:00 UTC")
            .containsEntry("end", "2025-01-02 12:30 UTC");
    }

    @Test
    void failedItemShouldNotHoldJavaWrappersNorRawICalendarLines() {
        Map<String, Object> values = failedItemValues("""
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:uid-1
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            SUMMARY:Event without end
            END:VEVENT
            END:VCALENDAR
            """);

        assertThat(values.get("start").toString()).doesNotStartWith("DTSTART").doesNotContain("\r", "\n");
        assertThat(values.get("end").toString()).doesNotStartWith("Optional").doesNotContain("DTEND", "\r", "\n");
    }

    @Test
    void failedItemShouldNotDisplayLineBreaksFromTheTzidParameter() {
        Map<String, Object> values = failedItemValues("""
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:uid-1
            DTSTAMP:20250101T100000Z
            DTSTART;TZID=Europe/Paris
            DTSTART:20250102T120000
            SUMMARY:Broken event
            END:VEVENT
            END:VCALENDAR
            """);

        assertThat(values.get("start").toString())
            .startsWith("2025-01-02 12:00")
            .doesNotContain("\r", "\n", "DTSTART");
    }
}
