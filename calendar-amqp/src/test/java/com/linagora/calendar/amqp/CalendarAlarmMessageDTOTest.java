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

package com.linagora.calendar.amqp;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class CalendarAlarmMessageDTOTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldReturnTrueWhenValarmExists() throws Exception {
        String json = """
            ["vcalendar", [],
              [["vevent", [],
                [["valarm", [], []]]
              ]]
            ]
            """;
        JsonNode node = mapper.readTree(json);
        assertThat(CalendarAlarmMessageDTO.hasVALARMComponent(node)).isTrue();
    }

    @Test
    void shouldReturnFalseWhenNoValarm() throws Exception {
        String json = """
            ["vcalendar", [],
              [["vevent", [],
                [["summary", {}, "text", "Meeting"]]
              ]]
            ]
            """;
        JsonNode node = mapper.readTree(json);
        assertThat(CalendarAlarmMessageDTO.hasVALARMComponent(node)).isFalse();
    }

    @Test
    void shouldReturnFalseForNullNode() {
        assertThat(CalendarAlarmMessageDTO.hasVALARMComponent(null)).isFalse();
    }

    @Test
    void shouldWorkWithObjectMixedStructure() throws Exception {
        String json = """
            {"vcalendar": ["vevent", [], [["valarm", [], []]]]}
            """;
        JsonNode node = mapper.readTree(json);
        assertThat(CalendarAlarmMessageDTO.hasVALARMComponent(node)).isTrue();
    }
}
