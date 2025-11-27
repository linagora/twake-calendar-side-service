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

package org.apache.james.events;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.CalendarChangeEvent;
import com.linagora.calendar.storage.CalendarURL;

public class CalendarEventSerializerTest {
    public static final String CALENDAR_CHANGE_JSON = """
        {
            "type": "CalendarEventSerializer$CalendarChangeDTO",
            "eventId": "34392fb0-8fc1-442e-bd33-5bd1af689f0e",
            "username": "calendarchange",
            "calendarUrl": "baseId/calendarId"
        }
        """;
    public static final CalendarChangeEvent CALENDAR_CHANGE_EVENT = new CalendarChangeEvent(
        Event.EventId.of("34392fb0-8fc1-442e-bd33-5bd1af689f0e"),
        CalendarURL.deserialize("baseId/calendarId"));

    private final CalendarEventSerializer serializer = new CalendarEventSerializer();

    @Test
    void shouldSerializeCalendarChangeEvent() {
        String json = serializer.toJson(CALENDAR_CHANGE_EVENT);
        assertThatJson(json).isEqualTo(CALENDAR_CHANGE_JSON);
    }

    @Test
    void shouldDeserializeJsonToCalendarChangeEvent() {
        Event event = serializer.asEvent(CALENDAR_CHANGE_JSON);
        assertThat(event).isEqualTo(CALENDAR_CHANGE_EVENT);
    }
}
