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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.apache.james.core.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.model.ImportId;

import reactor.core.publisher.Mono;

class ImportICSToDavHandlerTest {
    private static final Username BOB = Username.of("bob@open-paas.org");
    private static final String BASE_ID = "659387b9d486dc0046aeff21";

    private ImportProcessor.ImportICSToDavHandler testee;

    @BeforeEach
    void setUp() {
        CalDavClient calDavClient = mock(CalDavClient.class);
        when(calDavClient.importCalendar(any(CalendarURL.class), any(), any(Username.class), any()))
            .thenReturn(Mono.error(new DavClientException("rejected")));
        testee = new ImportProcessor.ImportICSToDavHandler(calDavClient);
    }

    @Test
    void rejectedEventWithDateStartAndTimeDurationShouldBeReportedAsFailed() {
        ImportProcessor.ImportResult result = testee.handle(command(ics("DTSTART;VALUE=DATE:20250102\r\nDURATION:PT1H")), BOB).block();

        assertThat(result.succeedCount()).isZero();
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().getFirst().keyValues())
            .containsEntry("uid", "all-day")
            .containsEntry("summary", "All day")
            .containsEntry("end", "");
    }

    @Test
    void rejectedEventWithUnparsableStartShouldBeReportedAsFailed() {
        ImportProcessor.ImportResult result = testee.handle(command(ics("DTSTART;TZID=Europe/Paris:20250q03T120000\r\nDURATION:PT1H")), BOB).block();

        assertThat(result.succeedCount()).isZero();
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().getFirst().keyValues()).containsEntry("uid", "all-day");
    }

    private ImportProcessor.ImportCommand command(String ics) {
        return new ImportProcessor.ImportCommand(ImportId.generate(),
            ImportProcessor.ImportType.ICS, ics.getBytes(StandardCharsets.UTF_8),
            new OpenPaaSId(BASE_ID), BASE_ID);
    }

    private String ics(String dateLines) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:all-day
            DTSTAMP:20250101T100000Z
            %s
            SUMMARY:All day
            END:VEVENT
            END:VCALENDAR
            """.replace("\n", "\r\n").formatted(dateLines);
    }
}
