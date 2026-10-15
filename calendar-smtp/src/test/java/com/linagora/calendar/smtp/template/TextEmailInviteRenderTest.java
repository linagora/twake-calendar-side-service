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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.i18n.I18NTranslator;

class TextEmailInviteRenderTest {
    private TextBodyRenderer textBodyRenderer;
    private I18NTranslator.PropertiesI18NTranslator.Factory i18nFactory;

    @BeforeEach
    void setUp() throws Exception {
        Path templateDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates", "event-invite");

        textBodyRenderer = TextBodyRenderer.forPath(templateDirectory.toAbsolutePath().toString()).orElseThrow();
        i18nFactory = new I18NTranslator.PropertiesI18NTranslator.Factory(templateDirectory.resolve("translations").toFile());
    }

    @Test
    void renderEventInviteShouldSucceed() {
        Map<String, Object> model = ImmutableMap.of(
            "content", ImmutableMap.builder()
                .put("event", ImmutableMap.builder()
                    .put("videoConferenceLink", "https://jitsi.linagora.com/11bb0e17")
                    .put("organizer", ImmutableMap.of("cn", "Alice Organizer", "email", "alice@domain.tld"))
                    .put("attendees", ImmutableMap.of(
                        "bob@domain.tld", ImmutableMap.of("cn", "Bob & Co", "email", "bob@domain.tld"),
                        "carol@domain.tld", ImmutableMap.of("email", "carol@domain.tld")))
                    .put("summary", "Team <Meeting>")
                    .put("allDay", false)
                    .put("start", ImmutableMap.of(
                        "date", "2025-06-27",
                        "fullDateTime", "2025-06-27 10:00",
                        "time", "10:00",
                        "timezone", "Europe/Paris",
                        "fullDate", "2025-06-27"))
                    .put("end", ImmutableMap.of(
                        "date", "2025-06-27",
                        "fullDateTime", "2025-06-27 11:00",
                        "time", "11:00",
                        "fullDate", "2025-06-27"))
                    .put("location", ImmutableMap.of(
                        "value", "Conference Room",
                        "urlEncodedValue", "Conference%20Room",
                        "isLocationAValidURL", false,
                        "isLocationAnAbsoluteURL", false))
                    .put("hasResources", true)
                    .put("resources", ImmutableMap.of(
                        "projector1", ImmutableMap.of("cn", "Projector"),
                        "roomA", ImmutableMap.of("cn", "Room A")))
                    .put("description", "Discuss project updates.\nBring snacks.")
                    .build())
                .put("seeInCalendarLink", "https://calendar.example.com/event/123")
                .put("yesLink", "https://calendar.example.com/event/123/yes")
                .put("maybeLink", "https://calendar.example.com/event/123/maybe")
                .put("noLink", "https://calendar.example.com/event/123/no")
                .build(),
            "translator", i18nFactory.forLocale(Locale.ENGLISH));

        assertThat(textBodyRenderer.render(model)).isEqualTo("""
            Alice Organizer has invited you in to an event
            Team <Meeting>

            Video conference
            https://jitsi.linagora.com/11bb0e17

            When
            2025-06-27 10:00 - 11:00 (Europe/Paris)
            See in Calendar: https://calendar.example.com/event/123

            Where
            Conference Room
            See in Map: https://www.openstreetmap.org/search?query=Conference%20Room

            Participants
            - Alice Organizer <alice@domain.tld> - organizer
            - Bob & Co <bob@domain.tld>
            - carol@domain.tld

            Resources
            - Projector
            - Room A

            Will you attend this event?
            Yes: https://calendar.example.com/event/123/yes
            No: https://calendar.example.com/event/123/no
            Maybe: https://calendar.example.com/event/123/maybe

            Notes
            Discuss project updates.
            Bring snacks.

            Forwarding this invitation could allow any recipient to send a response to the organizer, be added to the guest list, invite others regardless of their own invitation status, or modify your RSVP.
            """);
    }

    @Test
    void renderAllDayEventShouldIncludeAllDayLabel() {
        Map<String, Object> model = ImmutableMap.of(
            "content", ImmutableMap.builder()
                .put("event", ImmutableMap.builder()
                    .put("organizer", ImmutableMap.of("cn", "Alice", "email", "alice@domain.tld"))
                    .put("attendees", ImmutableMap.of(
                        "bob@domain.tld", ImmutableMap.of("cn", "Bob Attendee", "email", "bob@domain.tld")))
                    .put("summary", "All Day Workshop")
                    .put("allDay", true)
                    .put("start", ImmutableMap.of(
                        "date", "2025-07-01",
                        "fullDate", "July 1, 2025"))
                    .put("end", ImmutableMap.of(
                        "date", "2025-07-02",
                        "fullDate", "July 2, 2025"))
                    .put("location", ImmutableMap.of(
                        "value", "https://meet.example.com/room",
                        "isLocationAValidURL", true,
                        "isLocationAnAbsoluteURL", true))
                    .put("hasResources", false)
                    .build())
                .build(),
            "translator", i18nFactory.forLocale(Locale.FRENCH));

        assertThat(textBodyRenderer.render(model)).isEqualTo("""
            Alice vous a invité à un événement
            All Day Workshop

            Quand
            July 1, 2025 - July 2, 2025 (Journée entière)

            Où
            https://meet.example.com/room

            Participants
            - Alice <alice@domain.tld> - organisateur
            - Bob Attendee <bob@domain.tld>

            Participerez-vous à cet événement ?

            Transférer cette invitation pourrait permettre à tout destinataire d'envoyer une réponse à l'organisateur, d'être ajouté à la liste des invités, d'inviter d'autres personnes indépendamment de leur propre statut d'invitation ou de modifier votre réponse.
            """);
    }
}
