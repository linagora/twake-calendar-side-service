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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

public class CalendarReportJsonResponseTest {
    private static final String VALID_JSON = """
        {
           "_links": {
             "self": {
               "href": "\\/calendars\\/686f3edd543b4d0056c3f05d.json"
             }
           },
           "_embedded": {
             "dav:item": [
               {
                 "_links": {
                   "self": {
                     "href": "\\/calendars\\/686f3edd543b4d0056c3f05d\\/686f3edd543b4d0056c3f05d\\/sabredav-5016701f-069f-4295-aceb-a506f36551d0.ics"
                   }
                 },
                 "etag": "\\"52e34d8a14304dd239e03acc42732c41\\"",
                 "data": [
                   "vcalendar",
                   [
                     [
                       "version",
                       {},
                       "text",
                       "2.0"
                     ],
                     [
                       "prodid",
                       {},
                       "text",
                       "-//Sabre//Sabre VObject 4.2.2//EN"
                     ]
                   ],
                   [
                     [
                       "vevent",
                       [
                         [
                           "uid",
                           {},
                           "text",
                           "1234-5678-9012"
                         ],
                         [
                           "summary",
                           {},
                           "text",
                           "Team Meeting"
                         ],
                         [
                           "dtstart",
                           {
                             "tzid": "Asia/Ho_Chi_Minh"
                           },
                           "date-time",
                           "2025-07-11T09:30:00"
                         ],
                         [
                           "dtend",
                           {
                             "tzid": "Asia/Ho_Chi_Minh"
                           },
                           "date-time",
                           "2025-07-11T10:30:00"
                         ]
                       ],
                       []
                     ]
                   ]
                 ]
               }
             ]
           }
         }
        """;

    @Test
    void extractCalendarHrefShouldSucceed() throws JsonProcessingException {
        CalendarReportJsonResponse response = CalendarReportJsonResponse.from(VALID_JSON);
        assertThat(response.calendarHref().toString())
            .isEqualTo("""
                /calendars/686f3edd543b4d0056c3f05d/686f3edd543b4d0056c3f05d/sabredav-5016701f-069f-4295-aceb-a506f36551d0.ics""".trim());
    }

    @Test
    void davItemsShouldAllowEmptyResults() throws JsonProcessingException {
        CalendarReportJsonResponse response = CalendarReportJsonResponse.from("""
            {
              "_embedded": {
                "dav:item": []
              }
            }
            """);

        assertThat(response.davItems()).isEmpty();
        assertThatThrownBy(response::firstDavItemNode)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Expected non-empty 'dav:item' array in response");
    }

    @Test
    void davItemsShouldExposeEachEventsHrefAndData() throws JsonProcessingException {
        CalendarReportJsonResponse response = CalendarReportJsonResponse.from("""
            {
              "_embedded": {
                "dav:item": [
                  {
                    "_links": {
                      "self": {"href": "/calendars/first/first/event.ics"}
                    },
                    "data": ["vcalendar", [], []]
                  },
                  {
                    "_links": {
                      "self": {"href": "/calendars/second/second/event.ics"}
                    },
                    "data": ["vcalendar", [["version", {}, "text", "2.0"]], []]
                  }
                ]
              }
            }
            """);

        List<JsonNode> items = response.davItems();

        assertThat(items)
            .extracting(CalendarReportJsonResponse::calendarHref)
            .containsExactly(
                URI.create("/calendars/first/first/event.ics"),
                URI.create("/calendars/second/second/event.ics"));
        assertThatJson(VCalendarDto.from(items.get(1)).value())
            .isEqualTo("""
                ["vcalendar", [["version", {}, "text", "2.0"]], []]
                """);
    }
}
