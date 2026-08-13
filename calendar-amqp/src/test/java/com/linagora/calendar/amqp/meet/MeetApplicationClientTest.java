/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

class MeetApplicationClientTest {
    private static final String ROOMS_PATH = "/external-api/v1.0/rooms/";
    private static final String TOKEN = "test-app-jwt-token";
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

    private void stubCreateRoom(int status, String body) {
        wireMockServer.stubFor(post(urlEqualTo(ROOMS_PATH))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(status)
                .withBody(body)));
    }

    @Test
    void createRoomShouldReturnTheUrlMeetMinted() throws Exception {
        stubCreateRoom(201, "{\"id\":\"550e8400-e29b-41d4-a716-446655440000\","
            + "\"slug\":\"mjj-beyv-zai\",\"url\":\"" + ROOM_URL + "\"}");

        assertThat(client(Optional.empty()).createRoom(TOKEN).block()).isEqualTo(ROOM_URL);
    }

    @Test
    void createRoomShouldAuthenticateWithTheApplicationToken() throws Exception {
        stubCreateRoom(201, "{\"url\":\"" + ROOM_URL + "\"}");

        client(Optional.empty()).createRoom(TOKEN).block();

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

        client(Optional.empty()).createRoom(TOKEN).block();

        verify(postRequestedFor(urlEqualTo(ROOMS_PATH))
            .withRequestBody(matchingJsonPath("$.access_level", absent())));
    }

    @Test
    void createRoomShouldFailWhenMeetRejectsTheRequest() throws Exception {
        stubCreateRoom(403, "{\"detail\":\"missing rooms:create scope\"}");

        assertThatThrownBy(() -> client(Optional.empty()).createRoom(TOKEN).block())
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

        assertThatThrownBy(() -> client(Optional.empty()).createRoom(TOKEN).block())
            .isInstanceOf(MeetApplicationClient.MeetApiException.class)
            .hasMessageContaining("APPLICATION_BASE_URL");
    }
}
