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

package com.linagora.calendar.amqp.meet;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.linagora.calendar.amqp.CalendarEventMessage;

public class MeetHostDelegationServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ORGANIZER_EMAIL = "alice@example.com";
    private static final String DELEGATE_EMAIL = "bob@example.com";
    private static final String SECOND_DELEGATE_EMAIL = "carol@example.com";
    private static final String ROOM_SLUG = "team-standup";
    private static final String ROOM_UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String ACCESS_TOKEN = "test-app-jwt-token";

    private WireMockServer wireMockServer;
    private MeetHostDelegationService service;

    @BeforeEach
    void setUp() throws Exception {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        MeetConfiguration configuration = new MeetConfiguration(
            true,
            "test-client-id",
            "test-client-secret",
            URI.create("http://localhost:" + wireMockServer.port()),
            false,
            Duration.ofSeconds(5),
            Optional.empty());
        MeetApplicationClient client = new MeetApplicationClient(configuration);
        service = new MeetHostDelegationService(configuration, client);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void shouldGrantOnHappyPath() {
        stubTokenEndpoint();
        stubRoomsList(ROOM_SLUG, ROOM_UUID);
        stubGrantAccess(ROOM_UUID, 201);

        service.onEventSaved(eventMessage(ORGANIZER_EMAIL, meetUrl(ROOM_SLUG), DELEGATE_EMAIL))
            .block();

        verify(postRequestedFor(urlEqualTo("/external-api/v1.0/application/token/"))
            .withRequestBody(matchingJsonPath("$.scope", equalTo(ORGANIZER_EMAIL)))
            .withRequestBody(matchingJsonPath("$.grant_type", equalTo("client_credentials"))));
        verify(postRequestedFor(urlEqualTo("/external-api/v1.0/rooms/" + ROOM_UUID + "/grant-access/"))
            .withRequestBody(matchingJsonPath("$.email", equalTo(DELEGATE_EMAIL)))
            .withRequestBody(matchingJsonPath("$.role", equalTo("administrator"))));
    }

    @Test
    void shouldContinueWhenOneDelegateFails() {
        stubTokenEndpoint();
        stubRoomsList(ROOM_SLUG, ROOM_UUID);

        // First delegate: server returns 500. Second delegate: 201.
        wireMockServer.stubFor(post(urlPathEqualTo("/external-api/v1.0/rooms/" + ROOM_UUID + "/grant-access/"))
            .withRequestBody(matchingJsonPath("$.email", equalTo(DELEGATE_EMAIL)))
            .willReturn(aResponse().withStatus(500).withBody("boom")));
        wireMockServer.stubFor(post(urlPathEqualTo("/external-api/v1.0/rooms/" + ROOM_UUID + "/grant-access/"))
            .withRequestBody(matchingJsonPath("$.email", equalTo(SECOND_DELEGATE_EMAIL)))
            .willReturn(aResponse().withStatus(201).withBody("{\"created\":true}")));

        service.onEventSaved(eventMessage(ORGANIZER_EMAIL, meetUrl(ROOM_SLUG),
            DELEGATE_EMAIL + "," + SECOND_DELEGATE_EMAIL)).block();

        verify(postRequestedFor(urlPathEqualTo("/external-api/v1.0/rooms/" + ROOM_UUID + "/grant-access/"))
            .withRequestBody(matchingJsonPath("$.email", equalTo(DELEGATE_EMAIL))));
        verify(postRequestedFor(urlPathEqualTo("/external-api/v1.0/rooms/" + ROOM_UUID + "/grant-access/"))
            .withRequestBody(matchingJsonPath("$.email", equalTo(SECOND_DELEGATE_EMAIL))));
    }

    @Test
    void shouldNotCallMeetWhenNoDelegateHostsInIcs() {
        service.onEventSaved(eventMessageWithoutDelegates(ORGANIZER_EMAIL, meetUrl(ROOM_SLUG)))
            .block();

        verify(0, postRequestedFor(urlEqualTo("/external-api/v1.0/application/token/")));
    }

    @Test
    void shouldNotCallMeetWhenNoVideoconferenceInIcs() {
        service.onEventSaved(eventMessageWithoutMeetUrl(ORGANIZER_EMAIL, DELEGATE_EMAIL))
            .block();

        verify(0, postRequestedFor(urlEqualTo("/external-api/v1.0/application/token/")));
    }

    @Test
    void shouldNotThrowWhenTokenEndpointReturnsError() {
        wireMockServer.stubFor(post(urlEqualTo("/external-api/v1.0/application/token/"))
            .willReturn(aResponse().withStatus(401).withBody("Invalid credentials")));

        service.onEventSaved(eventMessage(ORGANIZER_EMAIL, meetUrl(ROOM_SLUG), DELEGATE_EMAIL))
            .block();

        verify(0, postRequestedFor(urlPathEqualTo("/external-api/v1.0/rooms/" + ROOM_UUID + "/grant-access/")));
    }

    @Test
    void shouldNotGrantWhenSlugNotFound() {
        stubTokenEndpoint();
        stubRoomsListEmpty();

        service.onEventSaved(eventMessage(ORGANIZER_EMAIL, meetUrl("unknown-slug"), DELEGATE_EMAIL))
            .block();

        verify(0, postRequestedFor(urlPathEqualTo("/external-api/v1.0/rooms/" + ROOM_UUID + "/grant-access/")));
    }

    @Test
    void shouldBeNoopWhenDisabled() throws Exception {
        MeetConfiguration disabled = MeetConfiguration.disabled();
        MeetApplicationClient noopClient = new MeetApplicationClient(disabled);
        MeetHostDelegationService disabledService = new MeetHostDelegationService(disabled, noopClient);

        disabledService.onEventSaved(eventMessage(ORGANIZER_EMAIL, meetUrl(ROOM_SLUG), DELEGATE_EMAIL))
            .block();

        verify(0, postRequestedFor(urlEqualTo("/external-api/v1.0/application/token/")));
    }

    @Test
    void extractSlugShouldReturnLastPathSegment() {
        assertThat(MeetHostDelegationService.extractSlug("https://meet.example.com/team-standup"))
            .contains("team-standup");
        assertThat(MeetHostDelegationService.extractSlug("https://meet.example.com/team-standup/"))
            .contains("team-standup");
        assertThat(MeetHostDelegationService.extractSlug("https://meet.example.com/"))
            .isEmpty();
        assertThat(MeetHostDelegationService.extractSlug("not a url"))
            .isEmpty();
    }

    private String meetUrl(String slug) {
        return "https://meet.example.com/" + slug;
    }

    private static CalendarEventMessage eventMessage(String organizerEmail,
                                                     String videoconferenceUrl,
                                                     String delegateHosts) {
        String json = """
            {
                "eventPath": "/calendars/domain1/calendar1/event1.ics",
                "event": [
                    "vcalendar",
                    [],
                    [
                        [
                            "vevent",
                            [
                                ["uid", {}, "text", "event-uid-1"],
                                ["dtstart", {}, "date-time", "2026-08-01T10:00:00Z"],
                                ["dtstamp", {}, "date-time", "2026-08-01T09:00:00Z"],
                                ["summary", {}, "text", "Team standup"],
                                ["organizer", {"cn":"Alice"}, "cal-address", "mailto:%s"],
                                ["x-openpaas-videoconference", {}, "text", "%s"],
                                ["x-twake-delegate-hosts", {}, "text", "%s"]
                            ],
                            []
                        ]
                    ]
                ],
                "import": false
            }""".formatted(organizerEmail, videoconferenceUrl, delegateHosts);
        return CalendarEventMessage.CreatedOrUpdated.deserialize(json.getBytes(StandardCharsets.UTF_8));
    }

    private static CalendarEventMessage eventMessageWithoutDelegates(String organizerEmail, String videoconferenceUrl) {
        String json = """
            {
                "eventPath": "/calendars/domain1/calendar1/event1.ics",
                "event": [
                    "vcalendar",
                    [],
                    [
                        [
                            "vevent",
                            [
                                ["uid", {}, "text", "event-uid-1"],
                                ["dtstart", {}, "date-time", "2026-08-01T10:00:00Z"],
                                ["dtstamp", {}, "date-time", "2026-08-01T09:00:00Z"],
                                ["summary", {}, "text", "Standup"],
                                ["organizer", {"cn":"Alice"}, "cal-address", "mailto:%s"],
                                ["x-openpaas-videoconference", {}, "text", "%s"]
                            ],
                            []
                        ]
                    ]
                ],
                "import": false
            }""".formatted(organizerEmail, videoconferenceUrl);
        return CalendarEventMessage.CreatedOrUpdated.deserialize(json.getBytes(StandardCharsets.UTF_8));
    }

    private static CalendarEventMessage eventMessageWithoutMeetUrl(String organizerEmail, String delegateHosts) {
        String json = """
            {
                "eventPath": "/calendars/domain1/calendar1/event1.ics",
                "event": [
                    "vcalendar",
                    [],
                    [
                        [
                            "vevent",
                            [
                                ["uid", {}, "text", "event-uid-1"],
                                ["dtstart", {}, "date-time", "2026-08-01T10:00:00Z"],
                                ["dtstamp", {}, "date-time", "2026-08-01T09:00:00Z"],
                                ["summary", {}, "text", "Meeting"],
                                ["organizer", {"cn":"Alice"}, "cal-address", "mailto:%s"],
                                ["x-twake-delegate-hosts", {}, "text", "%s"]
                            ],
                            []
                        ]
                    ]
                ],
                "import": false
            }""".formatted(organizerEmail, delegateHosts);
        return CalendarEventMessage.CreatedOrUpdated.deserialize(json.getBytes(StandardCharsets.UTF_8));
    }

    private void stubTokenEndpoint() {
        wireMockServer.stubFor(post(urlEqualTo("/external-api/v1.0/application/token/"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(200)
                .withBody("{\"access_token\":\"" + ACCESS_TOKEN + "\"}")));
    }

    private void stubRoomsList(String slug, String uuid) {
        String body;
        try {
            body = MAPPER.writeValueAsString(rooms(slug, uuid));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        wireMockServer.stubFor(get(urlEqualTo("/external-api/v1.0/rooms/"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(200)
                .withBody(body)));
    }

    private void stubRoomsListEmpty() {
        wireMockServer.stubFor(get(urlEqualTo("/external-api/v1.0/rooms/"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(200)
                .withBody("[]")));
    }

    private void stubGrantAccess(String roomUuid, int status) {
        wireMockServer.stubFor(post(urlPathEqualTo("/external-api/v1.0/rooms/" + roomUuid + "/grant-access/"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(status)
                .withBody("{\"created\":true}")));
    }

    private static JsonNode rooms(String slug, String uuid) throws Exception {
        String json = "[{\"id\":\"" + uuid + "\",\"slug\":\"" + slug + "\",\"name\":\"" + slug + "\"}]";
        return MAPPER.readTree(json);
    }
}
