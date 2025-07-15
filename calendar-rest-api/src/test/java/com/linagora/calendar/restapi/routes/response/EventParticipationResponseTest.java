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

package com.linagora.calendar.restapi.routes.response;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.api.EventParticipationActionLinkFactory.ActionLinks;
import com.linagora.calendar.dav.dto.VCalendarDto;

public class EventParticipationResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void jsonAsBytesShouldSerializeSuccessfully() throws Exception {
        VCalendarDto calendar = new VCalendarDto(MAPPER.readTree("""
                ["vcalendar", [["version", {}, "text", "2.0"]], []]
            """));

        MailAddress attendee = new MailAddress("test@example.com");

        ActionLinks links = new ActionLinks(
            URI.create("https://example.com/yes"),
            URI.create("https://example.com/no"),
            URI.create("https://example.com/maybe"));

        EventParticipationResponse response = new EventParticipationResponse(
            calendar,
            attendee,
            links,
            Locale.ENGLISH);

        byte[] jsonBytes = response.jsonAsBytes();

        String jsonString = new String(jsonBytes, StandardCharsets.UTF_8);
        assertThatJson(jsonString)
            .isEqualTo("""
                {
                  "eventJSON" : [ "vcalendar", [ [ "version", { }, "text", "2.0" ] ], [ ] ],
                  "attendeeEmail" : "test@example.com",
                  "locale" : "en",
                  "links" : {
                    "yes" : "https://example.com/yes",
                    "no" : "https://example.com/no",
                    "maybe" : "https://example.com/maybe"
                  }
                }""");
    }
}
