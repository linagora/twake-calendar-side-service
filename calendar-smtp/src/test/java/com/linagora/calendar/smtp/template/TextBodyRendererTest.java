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

package com.linagora.calendar.smtp.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.james.filesystem.api.FileSystem;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.i18n.I18NTranslator;

class TextBodyRendererTest {
    private static final Path TEMPLATES_DIRECTORY = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
        "app", "src", "main", "resources", "templates");

    static Stream<Path> templateDirectories() throws IOException {
        return TemplateTranslationsTest.templateDirectories();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("templateDirectories")
    void everyTemplateShouldDefineATextAlternative(Path templateDirectory) throws IOException {
        assertThat(TextBodyRenderer.forPath(templateDirectory.toString())).isPresent();
    }

    @Test
    void forPathShouldReturnEmptyWhenNoTextTemplate() throws IOException {
        FileSystem fileSystem = FileSystemImpl.forTesting();
        File templateDirectory = fileSystem.getFile("classpath://templates");

        assertThat(TextBodyRenderer.forPath(templateDirectory.getAbsolutePath())).isEmpty();
    }

    @Test
    void renderEventUpdateShouldMentionChangesAndPreviousValues() throws IOException {
        Map<String, Object> model = ImmutableMap.of(
            "content", ImmutableMap.builder()
                .put("event", ImmutableMap.builder()
                    .put("organizer", ImmutableMap.of("cn", "Alice Organizer", "email", "alice@domain.tld"))
                    .put("attendees", ImmutableMap.of(
                        "bob@domain.tld", ImmutableMap.of("cn", "Bob Attendee", "email", "bob@domain.tld")))
                    .put("summary", "Updated Meeting Title")
                    .put("allDay", false)
                    .put("start", ImmutableMap.of(
                        "date", "2025-07-06",
                        "fullDateTime", "2025-07-06 14:00",
                        "time", "14:00",
                        "timezone", "UTC",
                        "fullDate", "2025-07-06"))
                    .put("end", ImmutableMap.of(
                        "date", "2025-07-06",
                        "fullDateTime", "2025-07-06 15:00",
                        "time", "15:00",
                        "fullDate", "2025-07-06"))
                    .put("location", ImmutableMap.of(
                        "value", "https://meet.example.com/room",
                        "isLocationAValidURL", true,
                        "isLocationAnAbsoluteURL", true))
                    .put("hasResources", false)
                    .build())
                .put("seeInCalendarLink", "https://calendar.example.com/event/789")
                .put("yesLink", "https://calendar.example.com/event/789/yes")
                .put("maybeLink", "https://calendar.example.com/event/789/maybe")
                .put("noLink", "https://calendar.example.com/event/789/no")
                .put("changes", ImmutableMap.of(
                    "summary", ImmutableMap.of("previous", "Old Meeting Title"),
                    "dtstart", ImmutableMap.of("previous", ImmutableMap.of(
                        "date", "2025-07-06",
                        "fullDateTime", "2025-07-06 09:00",
                        "time", "09:00",
                        "fullDate", "2025-07-06")),
                    "dtend", ImmutableMap.of("previous", ImmutableMap.of(
                        "date", "2025-07-06",
                        "fullDateTime", "2025-07-06 10:00",
                        "time", "10:00",
                        "fullDate", "2025-07-06")),
                    "location", ImmutableMap.of("previous", "Old Conference Room"),
                    "description", ImmutableMap.of("previous", "Old agenda for the meeting."),
                    "isOldEventAllDay", false))
                .build());

        assertThat(render("event-update", model)).isEqualTo("""
            Alice Organizer has updated an event
            Updated Meeting Title [Changed]
            Previously: Old Meeting Title

            When [Changed]
            2025-07-06 14:00 - 15:00 (UTC)
            Previously: 2025-07-06 09:00 - 10:00
            See in Calendar: https://calendar.example.com/event/789

            Where [Changed]
            https://meet.example.com/room
            Previously: Old Conference Room

            Participants
            - Alice Organizer <alice@domain.tld> - organizer
            - Bob Attendee <bob@domain.tld>

            Will you attend this event?
            Yes: https://calendar.example.com/event/789/yes
            No: https://calendar.example.com/event/789/no
            Maybe: https://calendar.example.com/event/789/maybe

            Notes [Removed]
            Previously: Old agenda for the meeting.

            Forwarding this invitation could allow any recipient to send a response to the organizer, be added to the guest list, invite others regardless of their own invitation status, or modify your RSVP.
            """);
    }

    @Test
    void renderEventReplyShouldSucceed() throws IOException {
        Map<String, Object> model = ImmutableMap.of(
            "content", ImmutableMap.builder()
                .put("event", ImmutableMap.builder()
                    .put("organizer", ImmutableMap.of("cn", "Alice Organizer", "email", "alice@domain.tld"))
                    .put("attendees", ImmutableMap.of(
                        "bob@domain.tld", ImmutableMap.of("email", "bob@domain.tld")))
                    .put("summary", "Sprint review")
                    .put("allDay", true)
                    .put("start", ImmutableMap.of("date", "2025-07-01", "fullDate", "July 1, 2025"))
                    .put("end", ImmutableMap.of("date", "2025-07-01", "fullDate", "July 1, 2025"))
                    .put("location", ImmutableMap.of(
                        "value", "Room 1",
                        "urlEncodedValue", "Room%201",
                        "isValidURL", false,
                        "isAbsoluteURL", false))
                    .put("hasResources", true)
                    .put("resourcesJoined", "Projector, Room A")
                    .put("description", "Demo time")
                    .build())
                .put("replyPersonDisplayName", "Bob")
                .put("replyMessage", "has accepted this invitation")
                .build());

        assertThat(render("event-reply", model)).isEqualTo("""
            Bob has accepted this invitation
            Sprint review

            When
            July 1, 2025 (All day)

            Where
            Room 1
            See in Map: https://www.openstreetmap.org/search?query=Room%201

            Participants
            - Alice Organizer <alice@domain.tld> - organizer
            - bob@domain.tld

            Resources
            Projector, Room A

            Notes
            Demo time
            """);
    }

    @Test
    void renderImportCalendarShouldListFailures() throws IOException {
        Map<String, Object> model = ImmutableMap.of(
            "content", ImmutableMap.of(
                "baseUrl", "https://calendar.example.com",
                "jobSucceedCount", 3,
                "jobFailedCount", 1,
                "jobFailedList", List.of(ImmutableMap.of(
                    "uid", "uid-1",
                    "summary", "Broken event",
                    "start", "2025-07-01 10:00",
                    "end", "2025-07-01 11:00"))));

        assertThat(render("import-calendar", model)).isEqualTo("""
            Import Report

            Your Import is done see below the report :
            - 3 Event(s) imported successfully
            - 1 Event(s) not imported

            List of events who were not imported :
            - uid-1 - Broken event - 2025-07-01 10:00 / 2025-07-01 11:00
            """);
    }

    @Test
    void renderBookingRequestReceivedShouldSucceed() throws IOException {
        Map<String, Object> model = ImmutableMap.of(
            "content", ImmutableMap.builder()
                .put("start", ImmutableMap.of(
                    "date", "2025-07-01",
                    "fullDate", "Tuesday, 1 July 2025",
                    "time", "10:00",
                    "timezone", "Europe/Paris"))
                .put("end", ImmutableMap.of("date", "2025-07-01", "time", "11:00"))
                .put("owner", ImmutableMap.of("cn", "Alice", "email", "alice@domain.tld"))
                .put("reservationLink", "https://calendar.example.com/booking/123")
                .build());

        assertThat(render("event-booking-request-received", model))
            .contains("Tuesday, 1 July 2025", "10:00", "11:00", "Europe/Paris")
            .contains(": Alice <alice@domain.tld>")
            .endsWith(": https://calendar.example.com/booking/123\n");
    }

    private String render(String templateName, Map<String, Object> model) throws IOException {
        Path templateDirectory = TEMPLATES_DIRECTORY.resolve(templateName);
        I18NTranslator translator = new I18NTranslator.PropertiesI18NTranslator.Factory(templateDirectory.resolve("translations").toFile())
            .forLocale(Locale.ENGLISH);

        return TextBodyRenderer.forPath(templateDirectory.toString())
            .orElseThrow()
            .render(ImmutableMap.<String, Object>builder()
                .putAll(model)
                .put("translator", translator)
                .build());
    }
}
