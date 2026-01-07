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

import java.net.URI;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.CalendarChangeEvent;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.ImportEvent;
import com.linagora.calendar.storage.model.ImportId;

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

    public static final String IMPORT_EVENT_JSON = """
        {
            "type": "CalendarEventSerializer$ImportEventDTO",
            "eventId": "11111111-2222-3333-4444-555555555555",
            "importId": "import-123",
            "importURI": "baseId/calendarId",
            "importType": "ICS",
            "status":"success",
            "succeedCount": 10,
            "failedCount": 2
        }
        """;

    public static final ImportEvent IMPORT_EVENT = new ImportEvent(
        Event.EventId.of("11111111-2222-3333-4444-555555555555"),
        new ImportId("import-123"),
        URI.create("baseId/calendarId"),
        "ICS",
        ImportEvent.ImportStatus.SUCCESS,
        Optional.of(10),
        Optional.of(2));

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

    @Test
    void shouldSerializeImportEvent() {
        String json = serializer.toJson(IMPORT_EVENT);
        assertThatJson(json).isEqualTo(IMPORT_EVENT_JSON);
    }

    @Test
    void shouldDeserializeJsonToImportEvent() {
        Event event = serializer.asEvent(IMPORT_EVENT_JSON);
        assertThat(event).isEqualTo(IMPORT_EVENT);
    }
}
