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

class MeetApplicationCredentialsTokenProviderTest {
    private static final String TOKEN_PATH = "/external-api/v1.0/application/token/";
    private static final String USER_EMAIL = "organizer@example.com";
    private static final String TOKEN = "test-app-jwt-token";

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

    private MeetApplicationCredentialsTokenProvider tokenProvider() throws Exception {
        return new MeetApplicationCredentialsTokenProvider(new MeetConfiguration(
            true,
            "test-client-id",
            "test-client-secret",
            URI.create("http://localhost:" + wireMockServer.port()),
            false,
            Duration.ofSeconds(5),
            Optional.empty()));
    }

    private void stubToken(int status, String body) {
        wireMockServer.stubFor(post(urlEqualTo(TOKEN_PATH))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withStatus(status)
                .withBody(body)));
    }

    @Test
    void fetchTokenShouldReturnTheMintedToken() throws Exception {
        stubToken(200, "{\"access_token\":\"" + TOKEN + "\"}");

        assertThat(tokenProvider().fetchToken(USER_EMAIL).block()).isEqualTo(TOKEN);
    }

    @Test
    void fetchTokenShouldSendTheApplicationCredentialsScopedToTheUser() throws Exception {
        stubToken(200, "{\"access_token\":\"" + TOKEN + "\"}");

        tokenProvider().fetchToken(USER_EMAIL).block();

        verify(postRequestedFor(urlEqualTo(TOKEN_PATH))
            .withRequestBody(matchingJsonPath("$.client_id", equalTo("test-client-id")))
            .withRequestBody(matchingJsonPath("$.client_secret", equalTo("test-client-secret")))
            .withRequestBody(matchingJsonPath("$.grant_type", equalTo("client_credentials")))
            .withRequestBody(matchingJsonPath("$.scope", equalTo(USER_EMAIL))));
    }

    @Test
    void fetchTokenShouldFailWhenMeetRejectsTheExchange() throws Exception {
        stubToken(403, "{\"detail\":\"invalid client\"}");

        assertThatThrownBy(() -> tokenProvider().fetchToken(USER_EMAIL).block())
            .isInstanceOf(MeetApplicationClient.MeetApiException.class)
            .hasMessageContaining("403");
    }

    @Test
    void fetchTokenShouldFailWhenTheResponseCarriesNoToken() throws Exception {
        stubToken(200, "{}");

        assertThatThrownBy(() -> tokenProvider().fetchToken(USER_EMAIL).block())
            .isInstanceOf(MeetApplicationClient.MeetApiException.class)
            .hasMessageContaining("access_token");
    }
}
