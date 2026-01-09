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
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class VCalendarDtoTest {

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
                "data": [
                  "vcalendar",
                  [
                    ["version", {}, "text", "2.0"],
                    ["prodid", {}, "text", "-//Sabre//Sabre VObject 4.2.2//EN"]
                  ],
                  [
                    [
                      "vevent",
                      [
                        ["uid", {}, "text", "1234-5678-9012"],
                        ["summary", {}, "text", "Team Meeting"],
                        ["dtstart", {"tzid": "Asia/Ho_Chi_Minh"}, "date-time", "2025-07-11T09:30:00"],
                        ["dtend", {"tzid": "Asia/Ho_Chi_Minh"}, "date-time", "2025-07-11T10:30:00"]
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
    void shouldParseValidJsonSuccessfully() throws JsonProcessingException {
        CalendarReportJsonResponse response = CalendarReportJsonResponse.from(VALID_JSON);
        VCalendarDto vCalendarDto = VCalendarDto.from(response);
        assertThatJson(new ObjectMapper().writeValueAsString(vCalendarDto.value()))
            .isEqualTo("""
                [
                  "vcalendar",
                  [
                    ["version", {}, "text", "2.0"],
                    ["prodid", {}, "text", "-//Sabre//Sabre VObject 4.2.2//EN"]
                  ],
                  [
                    [
                      "vevent",
                      [
                        ["uid", {}, "text", "1234-5678-9012"],
                        ["summary", {}, "text", "Team Meeting"],
                        ["dtstart", {"tzid": "Asia/Ho_Chi_Minh"}, "date-time", "2025-07-11T09:30:00"],
                        ["dtend", {"tzid": "Asia/Ho_Chi_Minh"}, "date-time", "2025-07-11T10:30:00"]
                      ],
                      []
                    ]
                  ]
                ]
                """);
    }

    @Test
    void shouldThrowWhenEmbeddedMissing() throws JsonProcessingException {
        String missingEmbeddedJson = """
            {
                "someOtherField": {}
            }
            """;
        CalendarReportJsonResponse response = CalendarReportJsonResponse.from(missingEmbeddedJson);

        assertThatThrownBy(() -> VCalendarDto.from(response))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Missing '_embedded' field in response");
    }

    @Test
    void shouldThrowWhenDavItemIsEmpty() throws JsonProcessingException {
        String emptyDavItemJson = """
            {
                "_embedded": {
                    "dav:item": []
                }
            }
            """;

        CalendarReportJsonResponse response = CalendarReportJsonResponse.from(emptyDavItemJson);
        assertThatThrownBy(() -> VCalendarDto.from(response))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Expected non-empty 'dav:item' array in response");
    }

    @Test
    void shouldThrowWhenDataMissing() throws JsonProcessingException {
        String missingDataJson = """
            {
                "_embedded": {
                    "dav:item": [
                        {
                            "notData": []
                        }
                    ]
                }
            }
            """;
        CalendarReportJsonResponse response = CalendarReportJsonResponse.from(missingDataJson);
        assertThatThrownBy(() -> VCalendarDto.from(response))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Missing or invalid 'data' field in 'dav:item'");
    }
}
