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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DavResponseExceptionTest {
    private static final String SABRE_ERROR = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:error xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns">
          <s:sabredav-version>4.7.1</s:sabredav-version>
          <s:exception>Sabre\\DAV\\Exception\\Forbidden</s:exception>
          <s:message>Forbidden: You can not delete your main calendar</s:message>
          <s:file>/var/www/lib/JSON/Plugin.php</s:file>
          <s:stacktrace>#0 {main}</s:stacktrace>
        </d:error>
        """;

    @Test
    void davMessageShouldExtractSabreMessage() {
        DavResponseException exception = new DavResponseException(403, "deleting calendar '/calendars/abc/abc.json'", SABRE_ERROR);

        assertThat(exception.davMessage()).contains("Forbidden: You can not delete your main calendar");
    }

    @Test
    void davMessageShouldBeEmptyWhenBodyIsNotXml() {
        DavResponseException exception = new DavResponseException(500, "deleting calendar", "Internal error");

        assertThat(exception.davMessage()).isEmpty();
    }

    @Test
    void davMessageShouldBeEmptyWhenBodyIsEmpty() {
        DavResponseException exception = new DavResponseException(500, "deleting calendar", "");

        assertThat(exception.davMessage()).isEmpty();
    }

    @Test
    void davMessageShouldBeEmptyWhenNoSabreMessage() {
        DavResponseException exception = new DavResponseException(400, "deleting calendar",
            "<d:error xmlns:d=\"DAV:\"><d:message>other namespace</d:message></d:error>");

        assertThat(exception.davMessage()).isEmpty();
    }

    @Test
    void summaryShouldNotContainTheResponseBody() {
        DavResponseException exception = new DavResponseException(403, "deleting calendar '/calendars/abc/abc.json'", SABRE_ERROR);

        assertThat(exception.summary()).isEqualTo("Unexpected status code: 403 when deleting calendar '/calendars/abc/abc.json'");
    }

    @Test
    void messageShouldKeepTheResponseBodyForLogging() {
        DavResponseException exception = new DavResponseException(403, "deleting calendar '/calendars/abc/abc.json'", SABRE_ERROR);

        assertThat(exception.getMessage()).contains("Sabre\\DAV\\Exception\\Forbidden");
    }
}
