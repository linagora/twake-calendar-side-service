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

package com.linagora.calendar.twakespace.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.storage.model.TeamCalendarId;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;

class MeetingRequestTest {
    private static final String COMMAND = """
        {"specversion": "1.0", "id": "01J9ZB2W5Q3H7K8M9N0P1R2S3T", "source": "twake://space",
         "type": "com.twake.space.meeting.requested.v1", "time": "2026-10-08T10:02:11Z", "twakeorg": "linagora",
         "twakeactorid": "8f14e45f-ceea-467a-9575-1d1c2b0c4b2e", "twakeactor": "alice@space.tld",
         "data": {"uid": "6b0f6d1e-2f0a-4c55-9a43-7f1d1b9e2c10", "container": {"kind": "calendar", "id": "c41e9b07"},
                  "title": "Design review", "start": "2026-10-08T12:32:00+02:00", "end": "2026-10-08T13:02:00+02:00",
                  "timezone": "Europe/Paris", "description": "Last pass on the mockups"}}""";

    @Test
    void deserializeShouldReadTheCommand() throws Exception {
        assertThat(MeetingRequest.deserialize(COMMAND.getBytes(StandardCharsets.UTF_8)))
            .isEqualTo(new MeetingRequest("01J9ZB2W5Q3H7K8M9N0P1R2S3T", new MailAddress("alice@space.tld"), new TeamCalendarId("c41e9b07"),
                "6b0f6d1e-2f0a-4c55-9a43-7f1d1b9e2c10", "Design review", ZonedDateTime.parse("2026-10-08T12:32:00+02:00[Europe/Paris]"),
                ZonedDateTime.parse("2026-10-08T13:02:00+02:00[Europe/Paris]"), Optional.of("Last pass on the mockups")));
    }

    @Test
    void deserializeShouldPutTheTimesInTheTimezone() {
        String inUtc = COMMAND.replace("2026-10-08T12:32:00+02:00", "2026-10-08T10:32:00Z");

        assertThat(MeetingRequest.deserialize(inUtc.getBytes(StandardCharsets.UTF_8)).start())
            .isEqualTo(ZonedDateTime.parse("2026-10-08T12:32:00+02:00[Europe/Paris]"));
    }

    @Test
    void deserializeShouldAcceptNoDescription() {
        String withoutDescription = COMMAND.replace(", \"description\": \"Last pass on the mockups\"", "");

        assertThat(MeetingRequest.deserialize(withoutDescription.getBytes(StandardCharsets.UTF_8)).description()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"uid\": \"6b0f6d1e-2f0a-4c55-9a43-7f1d1b9e2c10\", ",
        "\"title\": \"Design review\", ",
        "\"timezone\": \"Europe/Paris\", ",
        "\"twakeactor\": \"alice@space.tld\",",
    })
    void deserializeShouldFailWhenAFieldIsMissing(String field) {
        String withoutField = COMMAND.replace(field, "");

        assertThatThrownBy(() -> MeetingRequest.deserialize(withoutField.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(UnprocessableSpaceEventException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"kind\": \"chat\"",
        "\"timezone\": \"Mars/Olympus\"",
        "\"end\": \"2026-10-08T12:00:00+02:00\"",
        "\"twakeactor\": \"not an address\"",
        "\"type\": \"com.twake.space.meeting.cancelled.v1\"",
    })
    void deserializeShouldFailOnAnInvalidField(String invalidField) {
        String field = invalidField.substring(0, invalidField.indexOf(':'));
        String invalid = COMMAND.replaceFirst(field + ": \"[^\"]*\"", invalidField);

        assertThatThrownBy(() -> MeetingRequest.deserialize(invalid.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(UnprocessableSpaceEventException.class);
    }

    @Test
    void asCalendarShouldDescribeTheMeetingWithItsRoom() {
        MeetingRequest request = MeetingRequest.deserialize(COMMAND.getBytes(StandardCharsets.UTF_8));

        Calendar calendar = request.asCalendar(URI.create("https://meet.space.tld/abc-defg-hij"), Instant.parse("2026-10-08T10:02:12Z"));

        VEvent event = (VEvent) calendar.getComponent(Component.VEVENT).orElseThrow();
        assertThat(calendar.getComponent(Component.VTIMEZONE)).isPresent();
        assertThat(event.getProperty(Property.UID).map(Property::getValue)).contains("6b0f6d1e-2f0a-4c55-9a43-7f1d1b9e2c10");
        assertThat(event.getProperty(Property.SUMMARY).map(Property::getValue)).contains("Design review");
        assertThat(event.getProperty(Property.DESCRIPTION).map(Property::getValue)).contains("Last pass on the mockups");
        assertThat(event.getProperty(Property.ORGANIZER).map(Property::getValue)).contains("mailto:alice@space.tld");
        assertThat(event.getProperty("X-OPENPAAS-VIDEOCONFERENCE").map(Property::getValue)).contains("https://meet.space.tld/abc-defg-hij");
        assertThat(event.getProperties(Property.ATTENDEE)).isEmpty();
        assertThat(calendar.toString())
            .contains("DTSTART;TZID=Europe/Paris:20261008T123200")
            .contains("DTEND;TZID=Europe/Paris:20261008T130200");
    }
}
