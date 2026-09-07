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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.linagora.calendar.amqp.CalendarEventMessage;

public class MeetHostDelegationServiceTest {
    private static final String TOKEN_PATH = "/external-api/v1.0/application/token/";
    private static final String ROOMS_PATH = "/external-api/v1.0/rooms/";
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
            Duration.ofSeconds(5));
        service = new MeetHostDelegationService(configuration,
            new MeetApplicationCredentialsTokenProvider(configuration),
            new MeetApplicationClient(configuration));
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void shouldGrantOnHappyPath() {
        stubTokenEndpoint(200, "{\"access_token\":\"" + ACCESS_TOKEN + "\"}");
        stubRoomsList("[{\"id\":\"" + ROOM_UUID + "\",\"slug\":\"" + ROOM_SLUG + "\",\"name\":\"" + ROOM_SLUG + "\"}]");
        stubGrantAccess(new GrantStub(ROOM_UUID, null, 201));

        service.onEventSaved(eventMessage(new EventSpec(ORGANIZER_EMAIL, meetUrl(ROOM_SLUG), DELEGATE_EMAIL)))
            .block();

        verify(postRequestedFor(urlEqualTo(TOKEN_PATH))
            .withRequestBody(matchingJsonPath("$.scope", equalTo(ORGANIZER_EMAIL)))
            .withRequestBody(matchingJsonPath("$.grant_type", equalTo("client_credentials"))));
        verifyGrantAccess(DELEGATE_EMAIL, 1);
    }

    @Test
    void shouldContinueWhenOneDelegateFails() {
        stubTokenEndpoint(200, "{\"access_token\":\"" + ACCESS_TOKEN + "\"}");
        stubRoomsList("[{\"id\":\"" + ROOM_UUID + "\",\"slug\":\"" + ROOM_SLUG + "\",\"name\":\"" + ROOM_SLUG + "\"}]");
        stubGrantAccess(new GrantStub(ROOM_UUID, DELEGATE_EMAIL, 500));
        stubGrantAccess(new GrantStub(ROOM_UUID, SECOND_DELEGATE_EMAIL, 201));

        service.onEventSaved(eventMessage(new EventSpec(ORGANIZER_EMAIL, meetUrl(ROOM_SLUG),
            DELEGATE_EMAIL + "," + SECOND_DELEGATE_EMAIL))).block();

        verifyGrantAccess(DELEGATE_EMAIL, 1);
        verifyGrantAccess(SECOND_DELEGATE_EMAIL, 1);
    }

    @Test
    void shouldNotCallMeetWhenNoDelegateHostsInIcs() {
        service.onEventSaved(eventMessage(new EventSpec(ORGANIZER_EMAIL, meetUrl(ROOM_SLUG), null)))
            .block();

        verify(0, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void shouldNotCallMeetWhenNoVideoconferenceInIcs() {
        service.onEventSaved(eventMessage(new EventSpec(ORGANIZER_EMAIL, null, DELEGATE_EMAIL)))
            .block();

        verify(0, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void shouldNotThrowWhenTokenEndpointReturnsError() {
        stubTokenEndpoint(401, "Invalid credentials");

        service.onEventSaved(eventMessage(new EventSpec(ORGANIZER_EMAIL, meetUrl(ROOM_SLUG), DELEGATE_EMAIL)))
            .block();

        verify(0, postRequestedFor(urlPathEqualTo(grantAccessPath(ROOM_UUID))));
    }

    @Test
    void shouldNotGrantWhenSlugNotFound() {
        stubTokenEndpoint(200, "{\"access_token\":\"" + ACCESS_TOKEN + "\"}");
        stubRoomsList("[]");

        service.onEventSaved(eventMessage(new EventSpec(ORGANIZER_EMAIL, meetUrl("unknown-slug"), DELEGATE_EMAIL)))
            .block();

        verify(0, postRequestedFor(urlPathEqualTo(grantAccessPath(ROOM_UUID))));
    }

    @Test
    void shouldBeNoopWhenDisabled() throws Exception {
        MeetConfiguration disabled = MeetConfiguration.disabled();
        MeetHostDelegationService disabledService = new MeetHostDelegationService(disabled,
            new MeetApplicationCredentialsTokenProvider(disabled),
            new MeetApplicationClient(disabled));

        disabledService.onEventSaved(eventMessage(new EventSpec(ORGANIZER_EMAIL, meetUrl(ROOM_SLUG), DELEGATE_EMAIL)))
            .block();

        verify(0, postRequestedFor(urlEqualTo(TOKEN_PATH)));
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

    private static String meetUrl(String slug) {
        return "https://meet.example.com/" + slug;
    }

    private static String grantAccessPath(String roomUuid) {
        return ROOMS_PATH + roomUuid + "/grant-access/";
    }

    /**
     * One VEVENT shape for {@link #eventMessage}: a null
     * {@code videoconferenceUrl} or {@code delegateHosts} omits the matching
     * ICS property — the two negative shapes the service must ignore.
     */
    private record EventSpec(String organizerEmail, String videoconferenceUrl, String delegateHosts) {
    }

    /** One grant-access call stub: status Meet answers for {@code delegateEmail} on {@code roomUuid}. */
    private record GrantStub(String roomUuid, String delegateEmail, int status) {
    }

    private static CalendarEventMessage eventMessage(EventSpec spec) {
        String meetProperty = spec.videoconferenceUrl() == null ? ""
            : ",\n[\"x-openpaas-videoconference\", {}, \"text\", \"%s\"]".formatted(spec.videoconferenceUrl());
        String delegatesProperty = spec.delegateHosts() == null ? ""
            : ",\n[\"x-twake-delegate-hosts\", {}, \"text\", \"%s\"]".formatted(spec.delegateHosts());
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
                                ["organizer", {"cn":"Alice"}, "cal-address", "mailto:%s"]%s%s
                            ],
                            []
                        ]
                    ]
                ],
                "import": false
            }""".formatted(spec.organizerEmail(), meetProperty, delegatesProperty);
        return CalendarEventMessage.CreatedOrUpdated.deserialize(json.getBytes(StandardCharsets.UTF_8));
    }

    private void stubTokenEndpoint(int status, String body) {
        wireMockServer.stubFor(post(urlEqualTo(TOKEN_PATH))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(status)
                .withBody(body)));
    }

    private void stubRoomsList(String body) {
        wireMockServer.stubFor(get(urlEqualTo(ROOMS_PATH))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(200)
                .withBody(body)));
    }

    private void stubGrantAccess(GrantStub grant) {
        var request = post(urlPathEqualTo(grantAccessPath(grant.roomUuid())));
        if (grant.delegateEmail() != null) {
            request = request.withRequestBody(matchingJsonPath("$.email", equalTo(grant.delegateEmail())));
        }
        wireMockServer.stubFor(request.willReturn(aResponse()
            .withHeader("Content-Type", "application/json")
            .withStatus(grant.status())
            .withBody("{\"created\":true}")));
    }

    private void verifyGrantAccess(String delegateEmail, int count) {
        verify(count, postRequestedFor(urlPathEqualTo(grantAccessPath(ROOM_UUID)))
            .withRequestBody(matchingJsonPath("$.email", equalTo(delegateEmail)))
            .withRequestBody(matchingJsonPath("$.role", equalTo("administrator"))));
    }
}
