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
import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.apache.james.core.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStore;
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
        testee = new ImportProcessor.ImportICSToDavHandler(calDavClient, ImportIcsRewriter.UNCHANGED);
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

    @Test
    void eventWithUnprintableDateShouldNotFailTheOtherEvents() {
        CalDavClient calDavClient = mock(CalDavClient.class);
        when(calDavClient.importCalendar(any(CalendarURL.class), any(), any(Username.class), any()))
            .thenReturn(Mono.empty());
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:good-event
            DTSTAMP:20260101T000000Z
            DTSTART:20260601T100000Z
            SUMMARY:Good event
            END:VEVENT
            BEGIN:VEVENT
            UID:bad-event
            DTSTAMP:20260101T000000Z
            DTSTART;TZID=Europe/Paris:
            SUMMARY:Event with an empty start
            END:VEVENT
            END:VCALENDAR
            """.replace("\n", "\r\n");

        ImportProcessor.ImportResult result = new ImportProcessor.ImportICSToDavHandler(calDavClient, ImportIcsRewriter.UNCHANGED)
            .handle(command(ics), BOB).block();

        assertThat(result.succeedCount()).isEqualTo(1);
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().getFirst().keyValues()).containsEntry("uid", "bad-event");
    }

    @Test
    void shouldReportTheAttachmentFailureAndImportTheOtherEvent() {
        CalDavClient calDavClient = mock(CalDavClient.class);
        when(calDavClient.importCalendar(any(CalendarURL.class), eq("plain"), eq(BOB), any()))
            .thenReturn(Mono.empty());
        AttachmentStore attachmentStore = mock(AttachmentStore.class);
        when(attachmentStore.upload(any(), eq(BOB))).thenReturn(Mono.error(
            new AttachmentStorageException(AttachmentStorageException.Reason.UPSTREAM_FAILURE, "Upload failed")));
        ImportProcessor.ImportICSToDavHandler handler =
            new ImportProcessor.ImportICSToDavHandler(calDavClient, new ImportAttachmentOffloader(attachmentStore));

        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:with-attachment
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            ATTACH;VALUE=BINARY;ENCODING=BASE64:dGVzdA==
            END:VEVENT
            BEGIN:VEVENT
            UID:plain
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            END:VEVENT
            END:VCALENDAR
            """.replace("\n", "\r\n");

        ImportProcessor.ImportResult result = handler.handle(command(calendarData), BOB).block();

        assertThat(result.succeedCount()).isEqualTo(1);
        assertThat(result.failed()).extracting(item -> item.keyValues().get("uid")).containsExactly("with-attachment");
        verify(attachmentStore).upload(assertArg(attachment ->
            assertThat(attachment.content()).isEqualTo("test".getBytes(StandardCharsets.UTF_8))), eq(BOB));
        verify(calDavClient).importCalendar(any(CalendarURL.class), eq("plain"), eq(BOB), any());
        verifyNoMoreInteractions(calDavClient);
    }

    @Test
    void shouldValidateAllAttachmentsBeforeUploadingAny() {
        CalDavClient calDavClient = mock(CalDavClient.class);
        AttachmentStore attachmentStore = mock(AttachmentStore.class);
        ImportProcessor.ImportICSToDavHandler handler =
            new ImportProcessor.ImportICSToDavHandler(calDavClient, new ImportAttachmentOffloader(attachmentStore));

        String calendarData = ics("""
            DTSTART:20250102T120000Z
            ATTACH;VALUE=BINARY;ENCODING=BASE64:dGVzdA==
            ATTACH;VALUE=BINARY;ENCODING=BASE64;X-FILENAME=../invalid.txt:dGVzdA==
            """.stripTrailing().replace("\n", "\r\n"));

        ImportProcessor.ImportResult result = handler.handle(command(calendarData), BOB).block();

        assertThat(result.succeedCount()).isZero();
        assertThat(result.failed()).hasSize(1);
        verifyNoInteractions(attachmentStore, calDavClient);
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
