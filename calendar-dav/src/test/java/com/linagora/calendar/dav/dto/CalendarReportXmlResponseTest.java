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

package com.linagora.calendar.dav.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.SAXException;

import com.linagora.calendar.dav.DavClientException;

class CalendarReportXmlResponseTest {

    @Test
    void shouldExtractOnlyDirectDavResponseHrefs() throws Exception {
        String xml = """
            <multistatus xmlns="DAV:" xmlns:other="urn:other">
              <href>/outside-response.ics</href>
              <response>
                <href>/event.ics</href>
                <other:href>/wrong-namespace.ics</other:href>
                <propstat><prop><href>/nested.ics</href></prop></propstat>
              </response>
              <other:response><href>/wrong-response-namespace.ics</href></other:response>
              <wrapper><response><href>/nested-response.ics</href></response></wrapper>
            </multistatus>
            """;
        CalendarReportXmlResponse response = new CalendarReportXmlResponse(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(response.extractCalendarObjectHrefs()).containsExactly(URI.create("/event.ics"));
    }

    @Test
    void shouldPreserveHrefEncodingOrderAndDuplicates() throws Exception {
        String xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href> /calendars/event%20%25&amp;one.ics </d:href></d:response>
              <d:response><d:href>https://dav.example.com/event%26two.ics</d:href></d:response>
              <d:response><d:href>relative%20event.ics</d:href></d:response>
              <d:response><d:href>/calendars/event%20%25&amp;one.ics</d:href></d:response>
            </d:multistatus>
            """;
        CalendarReportXmlResponse response = new CalendarReportXmlResponse(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(response.extractCalendarObjectHrefs()).containsExactly(
            URI.create("/calendars/event%20%25&one.ics"),
            URI.create("https://dav.example.com/event%26two.ics"),
            URI.create("relative%20event.ics"),
            URI.create("/calendars/event%20%25&one.ics"));
    }

    @Test
    void shouldReturnEmptyHrefsForEmptyMultistatus() throws Exception {
        CalendarReportXmlResponse response = new CalendarReportXmlResponse(
            "<d:multistatus xmlns:d=\"DAV:\"/>".getBytes(StandardCharsets.UTF_8));

        assertThat(response.extractCalendarObjectHrefs()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<invalid/>",
        "<multistatus/>",
        "<multistatus xmlns=\"urn:other\"/>",
        "<d:wrapper xmlns:d=\"DAV:\"><d:multistatus/></d:wrapper>"
    })
    void shouldRejectUnexpectedMultistatusRoot(String xml) {
        CalendarReportXmlResponse response = new CalendarReportXmlResponse(xml.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(response::extractCalendarObjectHrefs)
            .isInstanceOf(DavClientException.class)
            .hasMessage("Missing DAV:multistatus in REPORT response");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "<d:multistatus xmlns:d=\"DAV:\">"})
    void shouldRejectMalformedHrefReport(String xml) {
        CalendarReportXmlResponse response = new CalendarReportXmlResponse(xml.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(response::extractCalendarObjectHrefs).isInstanceOf(SAXException.class);
    }

    @Test
    void shouldRejectDoctypeInHrefReport() {
        String xml = """
            <!DOCTYPE multistatus [<!ENTITY href "event.ics">]>
            <multistatus xmlns="DAV:"><response><href>&href;</href></response></multistatus>
            """;
        CalendarReportXmlResponse response = new CalendarReportXmlResponse(xml.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(response::extractCalendarObjectHrefs).isInstanceOf(SAXException.class);
    }

    @Test
    void shouldRejectInvalidHref() {
        String xml = """
            <multistatus xmlns="DAV:"><response><href>event with spaces.ics</href></response></multistatus>
            """;
        CalendarReportXmlResponse response = new CalendarReportXmlResponse(xml.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(response::extractCalendarObjectHrefs).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCloseFailureShouldNotFailTheParse() throws Exception {
        XMLStreamReader reader = mock(XMLStreamReader.class);
        doThrow(new XMLStreamException("close failure")).when(reader).close();

        assertThatCode(() -> CalendarReportXmlResponse.close(reader)).doesNotThrowAnyException();
    }
}
