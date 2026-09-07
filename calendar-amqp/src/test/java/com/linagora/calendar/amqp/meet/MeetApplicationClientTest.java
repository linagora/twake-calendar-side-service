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
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

class MeetApplicationClientTest {
    private static final String ROOMS_PATH = "/external-api/v1.0/rooms/";
    private static final String GRANT_ACCESS_PATH = "/external-api/v1.0/rooms/room-uuid-1/grant-access/";
    private static final String TOKEN = "test-app-jwt-token";
    private static final String ROOM_ID = "room-uuid-1";
    private static final String ROOM_URL = "https://meet.example.com/mjj-beyv-zai";

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    private MeetApplicationClient client() throws Exception {
        return client(Optional.empty());
    }

    private MeetApplicationClient client(Optional<String> roomAccessLevel) throws Exception {
        return new MeetApplicationClient(new MeetConfiguration(
            true,
            "test-client-id",
            "test-client-secret",
            URI.create("http://localhost:" + wireMockServer.port()),
            false,
            Duration.ofSeconds(5),
            roomAccessLevel));
    }

    private void stubListRooms(int status, String body) {
        wireMockServer.stubFor(get(urlEqualTo(ROOMS_PATH))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(status)
                .withBody(body)));
    }

    private void stubCreateRoom(int status, String body) {
        wireMockServer.stubFor(post(urlEqualTo(ROOMS_PATH))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(status)
                .withBody(body)));
    }

    private void stubGrantAccess(int status, String body) {
        wireMockServer.stubFor(post(urlEqualTo(GRANT_ACCESS_PATH))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(status)
                .withBody(body)));
    }

    @Test
    void findRoomIdBySlugShouldReturnTheMatchingRoomId() throws Exception {
        stubListRooms(200, "{\"results\":[{\"id\":\"other-uuid\",\"slug\":\"abc-def-ghi\"},"
            + "{\"id\":\"" + ROOM_ID + "\",\"slug\":\"mjj-beyv-zai\"}]}");

        assertThat(client().findRoomIdBySlug(TOKEN, "mjj-beyv-zai").block()).contains(ROOM_ID);
    }

    @Test
    void findRoomIdBySlugShouldAuthenticateWithTheBearerToken() throws Exception {
        stubListRooms(200, "[]");

        client().findRoomIdBySlug(TOKEN, "mjj-beyv-zai").block();

        verify(getRequestedFor(urlEqualTo(ROOMS_PATH))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
    }

    @Test
    void findRoomIdBySlugShouldAcceptABareArrayResponse() throws Exception {
        stubListRooms(200, "[{\"id\":\"" + ROOM_ID + "\",\"slug\":\"mjj-beyv-zai\"}]");

        assertThat(client().findRoomIdBySlug(TOKEN, "mjj-beyv-zai").block()).contains(ROOM_ID);
    }

    @Test
    void findRoomIdBySlugShouldReturnEmptyWhenNoRoomMatches() throws Exception {
        stubListRooms(200, "{\"results\":[{\"id\":\"other-uuid\",\"slug\":\"abc-def-ghi\"}]}");

        assertThat(client().findRoomIdBySlug(TOKEN, "mjj-beyv-zai").block()).isEmpty();
    }

    @Test
    void findRoomIdBySlugShouldFailWhenMeetRejectsTheListing() throws Exception {
        stubListRooms(401, "{\"detail\":\"invalid token\"}");

        assertThatThrownBy(() -> client().findRoomIdBySlug(TOKEN, "mjj-beyv-zai").block())
            .isInstanceOf(MeetApplicationClient.MeetApiException.class)
            .hasMessageContaining("401");
    }

    /**
     * Meet's rooms listing is paginated (DRF). The match can sit beyond the
     * first page, so {@code next} must be followed until it runs out.
     */
    @Test
    void findRoomIdBySlugShouldFollowNextPagesUntilTheSlugMatches() throws Exception {
        String page2 = ROOMS_PATH + "?page=2";
        stubListRooms(200, "{\"results\":[{\"id\":\"other-uuid\",\"slug\":\"abc-def-ghi\"}],"
            + "\"next\":\"http://meet.internal:8000" + page2 + "\"}");
        wireMockServer.stubFor(get(urlEqualTo(page2))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(200)
                .withBody("{\"results\":[{\"id\":\"" + ROOM_ID + "\",\"slug\":\"mjj-beyv-zai\"}],\"next\":null}")));

        assertThat(client().findRoomIdBySlug(TOKEN, "mjj-beyv-zai").block()).contains(ROOM_ID);

        verify(getRequestedFor(urlEqualTo(page2))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
    }

    @Test
    void findRoomIdBySlugShouldReturnEmptyWhenPagesRunOut() throws Exception {
        String page2 = ROOMS_PATH + "?page=2";
        stubListRooms(200, "{\"results\":[{\"id\":\"other-uuid\",\"slug\":\"abc-def-ghi\"}],"
            + "\"next\":\"http://meet.internal:8000" + page2 + "\"}");
        wireMockServer.stubFor(get(urlEqualTo(page2))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(200)
                .withBody("{\"results\":[],\"next\":null}")));

        assertThat(client().findRoomIdBySlug(TOKEN, "mjj-beyv-zai").block()).isEmpty();
    }

    @Test
    void grantAccessShouldRequestTheAdministratorRole() throws Exception {
        stubGrantAccess(200, "{}");

        client().grantAccess(TOKEN, new MeetApplicationClient.RoomAccessGrant(ROOM_ID, "delegate@example.com")).block();

        verify(postRequestedFor(urlEqualTo(GRANT_ACCESS_PATH))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN))
            .withRequestBody(matchingJsonPath("$.email", equalTo("delegate@example.com")))
            .withRequestBody(matchingJsonPath("$.role", equalTo("administrator"))));
    }

    @Test
    void grantAccessShouldFailWhenMeetRejectsTheGrant() throws Exception {
        stubGrantAccess(404, "{\"detail\":\"room not found\"}");

        assertThatThrownBy(() -> client().grantAccess(TOKEN, new MeetApplicationClient.RoomAccessGrant(ROOM_ID, "delegate@example.com")).block())
            .isInstanceOf(MeetApplicationClient.MeetApiException.class)
            .hasMessageContaining("404");
    }

    @Test
    void createRoomShouldReturnTheUrlMeetMinted() throws Exception {
        stubCreateRoom(201, "{\"id\":\"550e8400-e29b-41d4-a716-446655440000\","
            + "\"slug\":\"mjj-beyv-zai\",\"url\":\"" + ROOM_URL + "\"}");

        assertThat(client().createRoom(TOKEN).block()).isEqualTo(ROOM_URL);
    }

    @Test
    void createRoomShouldAuthenticateWithTheApplicationToken() throws Exception {
        stubCreateRoom(201, "{\"url\":\"" + ROOM_URL + "\"}");

        client().createRoom(TOKEN).block();

        verify(postRequestedFor(urlEqualTo(ROOMS_PATH))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
    }

    @Test
    void createRoomShouldRequestTheConfiguredAccessLevel() throws Exception {
        stubCreateRoom(201, "{\"url\":\"" + ROOM_URL + "\"}");

        client(Optional.of("public")).createRoom(TOKEN).block();

        verify(postRequestedFor(urlEqualTo(ROOMS_PATH))
            .withRequestBody(matchingJsonPath("$.access_level", equalTo("public"))));
    }

    /**
     * The other polarity of the same branch. Without it, a client that always
     * sent an access level would pass the test above just as well — and it
     * would silently override Meet's own default for every deployment that
     * deliberately left the property unset.
     */
    @Test
    void createRoomShouldLeaveTheAccessLevelToMeetWhenUnconfigured() throws Exception {
        stubCreateRoom(201, "{\"url\":\"" + ROOM_URL + "\"}");

        client().createRoom(TOKEN).block();

        verify(postRequestedFor(urlEqualTo(ROOMS_PATH))
            .withRequestBody(matchingJsonPath("$.access_level", absent())));
    }

    @Test
    void createRoomShouldFailWhenMeetRejectsTheRequest() throws Exception {
        stubCreateRoom(403, "{\"detail\":\"missing rooms:create scope\"}");

        assertThatThrownBy(() -> client().createRoom(TOKEN).block())
            .isInstanceOf(MeetApplicationClient.MeetApiException.class)
            .hasMessageContaining("403")
            .hasMessageContaining("rooms:create");
    }

    /**
     * Meet composes {@code url} from its own {@code APPLICATION_BASE_URL}; the
     * field is absent when that setting is empty. Returning a room without a
     * link would put an empty href in a calendar invitation, so the failure
     * has to be loud and name the setting.
     */
    @Test
    void createRoomShouldFailWhenTheResponseCarriesNoUrl() throws Exception {
        stubCreateRoom(201, "{\"id\":\"550e8400-e29b-41d4-a716-446655440000\",\"slug\":\"mjj-beyv-zai\"}");

        assertThatThrownBy(() -> client().createRoom(TOKEN).block())
            .isInstanceOf(MeetApplicationClient.MeetApiException.class)
            .hasMessageContaining("APPLICATION_BASE_URL");
    }
}
