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

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.calendar.storage.CalendarURL;

public class CalendarListResponseTest {

    @Test
    void parseShouldExtractCalendarsWithTheirRawJson() {
        byte[] payload = """
            {
                 "_links": {
                     "self": {
                         "href": "/calendars/6053022c9da5ef001f430b43.json"
                     }
                 },
                 "_embedded": {
                     "dav:calendar": [
                         {
                             "_links": {
                                 "self": {
                                     "href": "/calendars/6053022c9da5ef001f430b43/6053022c9da5ef001f430b43.json"
                                 }
                             },
                             "dav:name": "My agenda (Default)",
                             "calendarserver:ctag": "http://sabre.io/ns/sync/4550",
                             "apple:color": "#4CAF50",
                             "apple:order": "1"
                         },
                         {
                             "_links": {
                                 "self": {
                                     "href": "/calendars/6053022c9da5ef001f430b43/158e8130-4388-4e46-8bb5-51eb9104aaef.json"
                                 }
                             },
                             "dav:name": "Archival",
                             "caldav:description": "",
                             "calendarserver:ctag": "http://sabre.io/ns/sync/1",
                             "apple:color": "#c06588"
                         }
                     ]
                 }
             }
            """.getBytes(StandardCharsets.UTF_8);

        CalendarListResponse response = CalendarListResponse.parse(payload);

        Map<CalendarURL, JsonNode> calendars = response.calendars();

        assertThat(calendars).hasSize(2);

        CalendarURL defaultCalendar = CalendarURL.parse("/calendars/6053022c9da5ef001f430b43/6053022c9da5ef001f430b43");
        CalendarURL archivalCalendar = CalendarURL.parse("/calendars/6053022c9da5ef001f430b43/158e8130-4388-4e46-8bb5-51eb9104aaef");

        assertThat(calendars)
            .containsKeys(defaultCalendar, archivalCalendar);

        assertThatJson(calendars.get(defaultCalendar).toString())
            .isEqualTo("""
                {
                  "_links": {
                    "self": {
                      "href": "/calendars/6053022c9da5ef001f430b43/6053022c9da5ef001f430b43.json"
                    }
                  },
                  "dav:name": "My agenda (Default)",
                  "calendarserver:ctag": "http://sabre.io/ns/sync/4550",
                  "apple:color": "#4CAF50",
                  "apple:order": "1"
                }
                """);

        assertThatJson(calendars.get(archivalCalendar).toString())
            .isEqualTo("""
                {
                  "_links": {
                    "self": {
                      "href": "/calendars/6053022c9da5ef001f430b43/158e8130-4388-4e46-8bb5-51eb9104aaef.json"
                    }
                  },
                  "dav:name": "Archival",
                  "caldav:description": "",
                  "calendarserver:ctag": "http://sabre.io/ns/sync/1",
                  "apple:color": "#c06588"
                }
                """);
    }
}
