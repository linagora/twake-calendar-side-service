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

package com.linagora.calendar.app;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.fge.lambdas.Throwing;
import com.google.inject.name.Names;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.OpenPaaSUser;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

public class TwakeCalendarAuthenticationTest {
    private static final String DOMAIN = "open-paas.ltd";
    private static final String USERINFO_TOKEN_URI_PATH = "/oauth2/userinfo";

    private static final ClientAndServer mockServer = ClientAndServer.startClientAndServer(0);

    private static URL getUserInfoTokenEndpoint() {
        return Throwing.supplier(() -> URI.create(String.format("http://127.0.0.1:%s%s", mockServer.getLocalPort(), USERINFO_TOKEN_URI_PATH)).toURL()).get();
    }

    @RegisterExtension
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        binder -> binder.bind(URL.class).annotatedWith(Names.named("userInfo"))
            .toProvider(TwakeCalendarAuthenticationTest::getUserInfoTokenEndpoint));

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        server.getProbe(CalendarDataProbe.class).addDomain(Domain.of(DOMAIN));

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setBasePath("/")
            .build();
    }

    @AfterEach
    void afterEach() {
        mockServer.reset();
    }

    private void updateMockerServerSpecifications(String response, int statusResponse) {
        mockServer
            .when(HttpRequest.request().withPath(USERINFO_TOKEN_URI_PATH))
            .respond(HttpResponse.response().withStatusCode(statusResponse)
                .withHeader("Content-Type", "application/json")
                .withBody(response, StandardCharsets.UTF_8));
    }

    @Test
    void shouldProvisionUserWhenAuthenticateWithOidcAndUserDoesNotExist(TwakeCalendarGuiceServer server) {
        String emailClaimValue = UUID.randomUUID() + "@" + DOMAIN;
        Username username = Username.of(emailClaimValue);

        assertThat(server.getProbe(CalendarDataProbe.class)
            .userId(username)).isNull();

        String activeResponse = """
            {
                "exp": 1652868271,
                "nbf": 0,
                "iat": 1652867971,
                "jti": "41ee3cc3-b908-4870-bff2-34b895b9fadf",
                "aud": "account",
                "typ": "Bearer",
                "acr": "1",
                "scope": "email",
                "email": "%s",
                "active": true
            }""".formatted(emailClaimValue);

        updateMockerServerSpecifications(activeResponse, 200);

        given()
            .header("Authorization", "Bearer oidc_opac_token")
            .when()
        .get("/api/themes/anything")
            .then()
            .statusCode(200);

        assertThat(server.getProbe(CalendarDataProbe.class)
            .userId(username)).isNotNull();
    }

    @Test
    void shouldNotErrorWhenAuthenticateWithOidcAndUserAlreadyExists(TwakeCalendarGuiceServer server) {
        String emailClaimValue = UUID.randomUUID() + "@" + DOMAIN;
        Username username = Username.of(emailClaimValue);

        server.getProbe(CalendarDataProbe.class)
            .addUser(username, "password");

        String activeResponse = """
            {
                "exp": 1652868271,
                "nbf": 0,
                "iat": 1652867971,
                "jti": "41ee3cc3-b908-4870-bff2-34b895b9fadf",
                "aud": "account",
                "typ": "Bearer",
                "acr": "1",
                "scope": "email",
                "email": "%s",
                "active": true
            }""".formatted(emailClaimValue);

        updateMockerServerSpecifications(activeResponse, 200);

        given()
            .header("Authorization", "Bearer oidc_opac_token")
            .when()
        .get("/api/themes/anything")
            .then()
            .statusCode(200);
    }

    @ParameterizedTest
    @CsvSource({
        "'', ''",
        "John, ''",
        "'', Doe",
        "John, Doe"
    })
    void shouldProvisionUserWithFirstnameAndSurnameWhenTheyArePresentInUserInfoResponse(String firstname, String lastname, TwakeCalendarGuiceServer server) throws JsonProcessingException {
        String emailClaimValue = UUID.randomUUID() + "@" + DOMAIN;
        Username username = Username.of(emailClaimValue);

        assertThat(server.getProbe(CalendarDataProbe.class).userId(username)).isNull();

        String activeResponse = """
            {
              "email" : "%s",
              "active" : true,
              "given_name" : "%s",
              "family_name" : "%s"
            }""".formatted(emailClaimValue, firstname, lastname);

        updateMockerServerSpecifications(activeResponse, 200);

        given()
            .header("Authorization", "Bearer " + "fakeToken")
        .when()
            .get("/api/themes/anything").prettyPeek()
        .then()
            .statusCode(200);

        OpenPaaSUser openPaaSUser = server.getProbe(CalendarDataProbe.class).getUser(username);
        assertThat(openPaaSUser).isNotNull();
        assertThat(openPaaSUser.firstname()).isEqualTo(firstname);
        assertThat(openPaaSUser.lastname()).isEqualTo(lastname);
    }

    @Test
    void shouldProvisionUserWithDefaultFirstnameAndSurnameWhenTheyAreAbsentInUserInfoResponse(TwakeCalendarGuiceServer server) {
        String emailClaimValue = UUID.randomUUID() + "@" + DOMAIN;
        Username username = Username.of(emailClaimValue);

        assertThat(server.getProbe(CalendarDataProbe.class).userId(username)).isNull();

        String activeResponse = """
            {
                "email": "%s",
                "active": true
            }""".formatted(emailClaimValue);

        updateMockerServerSpecifications(activeResponse, 200);

        given()
            .header("Authorization", "Bearer " + "fakeToken")
        .when()
            .get("/api/themes/anything")
        .then()
            .statusCode(200);

        OpenPaaSUser openPaaSUser = server.getProbe(CalendarDataProbe.class).getUser(username);
        assertThat(openPaaSUser).isNotNull();
        assertThat(openPaaSUser.firstname()).isEqualTo(username.asString());
        assertThat(openPaaSUser.lastname()).isEqualTo(username.asString());
    }

}