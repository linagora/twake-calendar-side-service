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

package com.linagora.calendar.dav.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class EventToImportTest {

    @Test
    void parseShouldSupportUnknownProperties() {
        byte[] ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-1
            DTSTART:20251020T100000Z
            MSUMARY:All day
            END:VEVENT
            END:VCALENDAR
            """.getBytes(StandardCharsets.UTF_8);

        assertThat(EventToImport.parse(ics))
            .singleElement()
            .satisfies(event -> assertThat(new String(event.ics(), StandardCharsets.UTF_8)).contains("MSUMARY:All day"));
    }

    @Test
    void parseShouldSplitEventsByUid() {
        byte[] ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-1
            DTSTART:20251020T100000Z
            MSUMARY:All day
            END:VEVENT
            BEGIN:VEVENT
            UID:event-2
            DTSTART:20251021T100000Z
            SUMMARY:Other
            END:VEVENT
            END:VCALENDAR
            """.getBytes(StandardCharsets.UTF_8);

        assertThat(EventToImport.parse(ics))
            .hasSize(2)
            .allSatisfy(event -> assertThat(new String(event.ics(), StandardCharsets.UTF_8))
                .contains("UID:" + event.uid())
                .containsOnlyOnce("BEGIN:VEVENT"));
    }
}
