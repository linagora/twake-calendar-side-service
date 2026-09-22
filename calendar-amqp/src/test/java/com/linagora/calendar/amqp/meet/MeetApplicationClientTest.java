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

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.fge.lambdas.Throwing;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

class MeetApplicationClientTest {
    private static final String ROOMS_PATH = "/external-api/v1.0/rooms/";
    private static final MeetApplicationClient.MeetToken TOKEN = new MeetApplicationClient.MeetToken("test-app-jwt-token");
    private static final String TOKEN_PATH = "/external-api/v1.0/application/token/";
    private static final MailAddress USER_EMAIL = Throwing.supplier(() -> new MailAddress("organizer@example.com")).get();
    private static final String ROOM_URL = "https://meet.example.com/mjj-beyv-zai";
    private static final String ROOM_UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String ROOM_JSON = "{\"id\":\"" + ROOM_UUID + "\",\"slug\":\"mjj-beyv-zai\","
        + "\"url\":\"" + ROOM_URL + "\"}";

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

    private MeetApplicationClient client() {
        return client(Optional.empty());
    }

    private MeetApplicationClient client(Optional<String> roomAccessLevel) {
        return new MeetApplicationClient(configuration(roomAccessLevel));
    }

    static MeetConfiguration configuration(Optional<String> roomAccessLevel, int port) {
        return new MeetConfiguration(
            "test-client-id",
            "test-client-secret",
            URI.create("http://localhost:" + port),
            false,
            Duration.ofSeconds(5),
            roomAccessLevel);
    }

    private MeetConfiguration configuration(Optional<String> roomAccessLevel) {
        return configuration(roomAccessLevel, wireMockServer.port());
    }

    private void stubCreateRoom(int status, String body) {
        wireMockServer.stubFor(post(urlEqualTo(ROOMS_PATH))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(status)
                .withBody(body)));
    }

    @Test
    void createRoomShouldReturnTheUrlMeetMinted() {
        stubCreateRoom(201, ROOM_JSON);

        assertThat(client().createRoom(TOKEN).block().url()).isEqualTo(ROOM_URL);
    }

    @Test
    void createRoomShouldAuthenticateWithTheApplicationToken() {
        stubCreateRoom(201, ROOM_JSON);

        client().createRoom(TOKEN).block();

        verify(postRequestedFor(urlEqualTo(ROOMS_PATH))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN.value())));
    }

    @Test
    void createRoomShouldRequestTheConfiguredAccessLevel() {
        stubCreateRoom(201, ROOM_JSON);

        client(Optional.of("public")).createRoom(TOKEN).block();

        verify(postRequestedFor(urlEqualTo(ROOMS_PATH))
            .withRequestBody(matchingJsonPath("$.access_level", equalTo("public"))));
    }

    @Test
    void createRoomShouldLeaveTheAccessLevelToMeetWhenUnconfigured() {
        stubCreateRoom(201, ROOM_JSON);

        client().createRoom(TOKEN).block();

        verify(postRequestedFor(urlEqualTo(ROOMS_PATH))
            .withRequestBody(matchingJsonPath("$.access_level", absent())));
    }

    @Test
    void createRoomShouldRetryWhenMeetDrawsAnAlreadyTakenSlug() {
        wireMockServer.stubFor(post(urlEqualTo(ROOMS_PATH))
            .inScenario("slug collision")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(400)
                .withBody("{\"slug\":[\"Room with this Slug already exists.\"]}"))
            .willSetStateTo("drawn again"));
        wireMockServer.stubFor(post(urlEqualTo(ROOMS_PATH))
            .inScenario("slug collision")
            .whenScenarioStateIs("drawn again")
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(201)
                .withBody(ROOM_JSON)));

        assertThat(client().createRoom(TOKEN).block().url()).isEqualTo(ROOM_URL);

        verify(2, postRequestedFor(urlEqualTo(ROOMS_PATH)));
    }

    @Test
    void createRoomShouldSurfaceMeetRefusalWhenCollisionsOutlastTheRetries() {
        stubCreateRoom(400, "{\"slug\":[\"Room with this Slug already exists.\"]}");

        assertThatThrownBy(() -> client().createRoom(TOKEN).block())
            .isInstanceOf(MeetApplicationClient.MeetApiException.class)
            .hasMessageContaining("Slug already exists");
    }

    @Test
    void createRoomShouldNotRetryOtherRejections() {
        stubCreateRoom(400, "{\"access_level\":[\"Public rooms are disabled for the external API.\"]}");

        assertThatThrownBy(() -> client().createRoom(TOKEN).block())
            .isInstanceOf(MeetApplicationClient.MeetApiException.class)
            .hasMessageContaining("Public rooms are disabled");

        verify(1, postRequestedFor(urlEqualTo(ROOMS_PATH)));
    }

    @Test
    void createRoomShouldFailWhenMeetRejectsTheRequest() {
        stubCreateRoom(403, "{\"detail\":\"missing rooms:create scope\"}");

        assertThatThrownBy(() -> client().createRoom(TOKEN).block())
            .isInstanceOf(MeetApplicationClient.MeetApiException.class)
            .hasMessageContaining("403")
            .hasMessageContaining("rooms:create");
    }

    @Test
    void createRoomShouldFailWhenTheResponseCarriesNoUrl() {
        stubCreateRoom(201, "{\"id\":\"" + ROOM_UUID + "\",\"slug\":\"mjj-beyv-zai\"}");

        assertThatThrownBy(() -> client().createRoom(TOKEN).block())
            .isInstanceOf(MeetApplicationClient.MeetApiException.class)
            .hasMessageContaining("APPLICATION_BASE_URL");
    }

    @Test
    void fetchTokenShouldReturnTheMintedToken() {
        stubToken(200, "{\"access_token\":\"" + TOKEN.value() + "\"}");

        assertThat(client().fetchToken(USER_EMAIL).block()).isEqualTo(TOKEN);
    }

    @Test
    void fetchTokenShouldSendTheApplicationCredentialsScopedToTheUser() {
        stubToken(200, "{\"access_token\":\"" + TOKEN.value() + "\"}");

        client().fetchToken(USER_EMAIL).block();

        verify(postRequestedFor(urlEqualTo(TOKEN_PATH))
            .withRequestBody(matchingJsonPath("$.client_id", equalTo("test-client-id")))
            .withRequestBody(matchingJsonPath("$.client_secret", equalTo("test-client-secret")))
            .withRequestBody(matchingJsonPath("$.grant_type", equalTo("client_credentials")))
            .withRequestBody(matchingJsonPath("$.scope", equalTo(USER_EMAIL.asString()))));
    }

    @Test
    void fetchTokenShouldFailWhenMeetRejectsTheExchange() {
        stubToken(403, "{\"detail\":\"invalid client\"}");

        assertThatThrownBy(() -> client().fetchToken(USER_EMAIL).block())
            .isInstanceOf(MeetApplicationClient.MeetApiException.class)
            .hasMessageContaining("403");
    }

    @Test
    void fetchTokenShouldFailWhenTheResponseCarriesNoToken() {
        stubToken(200, "{}");

        assertThatThrownBy(() -> client().fetchToken(USER_EMAIL).block())
            .isInstanceOf(MeetApplicationClient.MeetApiException.class)
            .hasMessageContaining("access_token");
    }

    @Test
    void errorsShouldNotQuoteTheWholeResponseBody() {
        stubCreateRoom(500, "x".repeat(10_000));

        assertThatThrownBy(() -> client().createRoom(TOKEN).block())
            .isInstanceOf(MeetApplicationClient.MeetApiException.class)
            .satisfies(error -> assertThat(error.getMessage().length()).isLessThan(512));
    }

    private void stubToken(int status, String body) {
        wireMockServer.stubFor(post(urlEqualTo(TOKEN_PATH))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(status)
                .withBody(body)));
    }
}
