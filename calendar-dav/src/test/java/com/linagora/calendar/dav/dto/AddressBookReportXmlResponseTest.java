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

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AddressBookReportXmlResponseTest {

    private static AddressBookReportXmlResponse response(String xml) {
        return new AddressBookReportXmlResponse(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void countContactsShouldReturnZeroWhenNoResponse() {
        assertThat(response("""
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav"/>
            """).countContacts())
            .isEqualTo(0L);
    }

    @Test
    void countContactsShouldCountResponsesWithoutContactData() {
        assertThat(response("""
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
              <d:response>
                <d:href>/addressbooks/ownerId/contacts/contact1.vcf</d:href>
                <d:propstat>
                  <d:prop><d:getetag>"etag1"</d:getetag></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/addressbooks/ownerId/contacts/contact2.vcf</d:href>
                <d:propstat>
                  <d:prop><d:getetag>"etag2"</d:getetag></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
            """).countContacts())
            .isEqualTo(2L);
    }
}
