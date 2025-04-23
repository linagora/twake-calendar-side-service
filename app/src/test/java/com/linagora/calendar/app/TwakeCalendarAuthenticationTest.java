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
import static io.restassured.RestAssured.with;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.jwt.introspection.IntrospectionEndpoint;
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

class TwakeCalendarAuthenticationTest {
    private static final String DOMAIN = "open-paas.ltd";
    private static final String USERINFO_TOKEN_URI_PATH = "/oauth2/userinfo";
    private static final String INTROSPECT_TOKEN_URI_PATH = "/oauth2/introspect";

    private static final ClientAndServer mockServer = ClientAndServer.startClientAndServer(0);

    private static URL getUserInfoTokenEndpoint() {
        return Throwing.supplier(() -> URI.create(String.format("http://127.0.0.1:%s%s", mockServer.getLocalPort(), USERINFO_TOKEN_URI_PATH)).toURL()).get();
    }

    private static URL getInrospectTokenEndpoint() {
        return Throwing.supplier(() -> URI.create(String.format("http://127.0.0.1:%s%s", mockServer.getLocalPort(), INTROSPECT_TOKEN_URI_PATH)).toURL()).get();
    }

    @RegisterExtension
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        binder -> binder.bind(URL.class).annotatedWith(Names.named("userInfo"))
            .toProvider(TwakeCalendarAuthenticationTest::getUserInfoTokenEndpoint),
        binder -> binder.bind(IntrospectionEndpoint.class).toProvider(() -> new IntrospectionEndpoint(getInrospectTokenEndpoint(), Optional.empty())));

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

    private void updateMockerServerSpecifications(String path, String response, int statusResponse) {
        mockServer
            .when(HttpRequest.request().withPath(path))
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

        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, """
            {
              "sub": "twake-calendar-dev",
              "email": "%s",
              "family_name": "twake-calendar-dev",
              "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
              "name": "twake-calendar-dev"
            }""".formatted(emailClaimValue), 200);

        updateMockerServerSpecifications(INTROSPECT_TOKEN_URI_PATH, """
            {
                "exp": %d,
                "scope": "openid email profile",
                "client_id": "tcalendar",
                "active": true,
                "aud": "tcalendar",
                "sub": "twake-calendar-dev",
                "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
                "iss": "https://sso.linagora.com"
              }
              """.formatted(Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond()), 200);

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
              "sub": "twake-calendar-dev",
              "email": "%s",
              "family_name": "twake-calendar-dev",
              "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
              "name": "twake-calendar-dev"
            }""".formatted(emailClaimValue);

        updateMockerServerSpecifications(INTROSPECT_TOKEN_URI_PATH, """
            {
                "exp": %d,
                "scope": "openid email profile",
                "client_id": "tcalendar",
                "active": true,
                "aud": "tcalendar",
                "sub": "twake-calendar-dev",
                "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
                "iss": "https://sso.linagora.com"
              }
              """.formatted(Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond()), 200);

        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, activeResponse, 200);

        given()
            .header("Authorization", "Bearer oidc_opac_token")
        .when()
            .get("/api/themes/anything")
        .then()
            .statusCode(200);
    }

    @Test
    void shouldRejectOutdatedToken(TwakeCalendarGuiceServer server) {
        String emailClaimValue = UUID.randomUUID() + "@" + DOMAIN;

        String activeResponse = """
            {
              "sub": "twake-calendar-dev",
              "email": "%s",
              "family_name": "twake-calendar-dev",
              "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
              "name": "twake-calendar-dev"
            }""".formatted(emailClaimValue);

        updateMockerServerSpecifications(INTROSPECT_TOKEN_URI_PATH, """
            {
                "exp": %d,
                "scope": "openid email profile",
                "client_id": "tcalendar",
                "active": true,
                "aud": "tcalendar",
                "sub": "twake-calendar-dev",
                "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
                "iss": "https://sso.linagora.com"
              }
              """.formatted(Clock.systemUTC().instant().minus(Duration.ofHours(1)).getEpochSecond()), 200);

        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, activeResponse, 200);

        given()
            .header("Authorization", "Bearer oidc_opac_token")
        .when()
            .get("/api/themes/anything")
        .then()
            .statusCode(401);
    }

    @Test
    void shouldRejectBadAudience(TwakeCalendarGuiceServer server) {
        String emailClaimValue = UUID.randomUUID() + "@" + DOMAIN;

        String activeResponse = """
            {
              "sub": "twake-calendar-dev",
              "email": "%s",
              "family_name": "twake-calendar-dev",
              "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
              "name": "twake-calendar-dev"
            }""".formatted(emailClaimValue);

        updateMockerServerSpecifications(INTROSPECT_TOKEN_URI_PATH, """
            {
                "exp": %d,
                "scope": "openid email profile",
                "client_id": "tcalendar",
                "active": true,
                "aud": "bad",
                "sub": "twake-calendar-dev",
                "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
                "iss": "https://sso.linagora.com"
              }
              """.formatted(Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond()), 200);

        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, activeResponse, 200);

        given()
            .header("Authorization", "Bearer oidc_opac_token")
        .when()
            .get("/api/themes/anything")
        .then()
            .statusCode(401);
    }

    @Test
    void shouldAcceptNoSidInUserInfo(TwakeCalendarGuiceServer server) {
        String emailClaimValue = UUID.randomUUID() + "@" + DOMAIN;

        String activeResponse = """
            {
              "sub": "twake-calendar-dev",
              "email": "%s",
              "family_name": "twake-calendar-dev",
              "name": "twake-calendar-dev"
            }""".formatted(emailClaimValue);

        updateMockerServerSpecifications(INTROSPECT_TOKEN_URI_PATH, """
            {
                "exp": %d,
                "scope": "openid email profile",
                "client_id": "tcalendar",
                "active": true,
                "aud": "tcalendar",
                "sub": "twake-calendar-dev",
                "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
                "iss": "https://sso.linagora.com"
              }
              """.formatted(Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond()), 200);

        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, activeResponse, 200);

        given()
            .header("Authorization", "Bearer oidc_opac_token")
        .when()
            .get("/api/themes/anything")
        .then()
            .statusCode(200);
    }

    @Test
    void shouldAcceptNoSidInIntrospection(TwakeCalendarGuiceServer server) {
        String emailClaimValue = UUID.randomUUID() + "@" + DOMAIN;

        String activeResponse = """
            {
              "sub": "twake-calendar-dev",
              "email": "%s",
              "family_name": "twake-calendar-dev",
              "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
              "name": "twake-calendar-dev"
            }""".formatted(emailClaimValue);

        updateMockerServerSpecifications(INTROSPECT_TOKEN_URI_PATH, """
            {
                "exp": %d,
                "scope": "openid email profile",
                "client_id": "tcalendar",
                "active": true,
                "aud": "tcalendar",
                "sub": "twake-calendar-dev",
                "iss": "https://sso.linagora.com"
              }
              """.formatted(Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond()), 200);

        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, activeResponse, 200);

        given()
            .header("Authorization", "Bearer oidc_opac_token")
        .when()
            .get("/api/themes/anything")
        .then()
            .statusCode(200);
    }

    @Test
    void shouldTolerateNoSid(TwakeCalendarGuiceServer server) {
        String emailClaimValue = UUID.randomUUID() + "@" + DOMAIN;

        String activeResponse = """
            {
              "sub": "twake-calendar-dev",
              "email": "%s",
              "family_name": "twake-calendar-dev",
              "name": "twake-calendar-dev"
            }""".formatted(emailClaimValue);

        updateMockerServerSpecifications(INTROSPECT_TOKEN_URI_PATH, """
            {
                "exp": %d,
                "scope": "openid email profile",
                "client_id": "tcalendar",
                "active": true,
                "aud": "tcalendar",
                "sub": "twake-calendar-dev",
                "iss": "https://sso.linagora.com"
              }
              """.formatted(Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond()), 200);

        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, activeResponse, 200);

        given()
            .header("Authorization", "Bearer oidc_opac_token")
        .when()
            .get("/api/themes/anything")
        .then()
            .statusCode(200);
    }

    @Test
    void shouldCacheResponse(TwakeCalendarGuiceServer server) {
        String emailClaimValue = UUID.randomUUID() + "@" + DOMAIN;
        String activeResponse = """
            {
              "sub": "twake-calendar-dev",
              "email": "%s",
              "family_name": "twake-calendar-dev",
              "name": "twake-calendar-dev"
            }""".formatted(emailClaimValue);
        updateMockerServerSpecifications(INTROSPECT_TOKEN_URI_PATH, """
            {
                "exp": %d,
                "scope": "openid email profile",
                "client_id": "tcalendar",
                "active": true,
                "aud": "tcalendar",
                "sub": "twake-calendar-dev",
                "iss": "https://sso.linagora.com"
              }
              """.formatted(Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond()), 200);
        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, activeResponse, 200);
        with()
            .header("Authorization", "Bearer oidc_opac_token")
            .get("/api/themes/anything")
        .then()
            .statusCode(200);

        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, activeResponse, 401); // not called
        given()
            .header("Authorization", "Bearer oidc_opac_token")
        .when()
            .get("/api/themes/anything")
        .then()
            .statusCode(200);
    }

    @Test
    void shouldRejectWhenUserInfoFails(TwakeCalendarGuiceServer server) {
        updateMockerServerSpecifications(INTROSPECT_TOKEN_URI_PATH, """
            {
                "exp": %d,
                "scope": "openid email profile",
                "client_id": "tcalendar",
                "active": true,
                "aud": "tcalendar",
                "sub": "twake-calendar-dev",
                "iss": "https://sso.linagora.com"
              }
              """.formatted(Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond()), 200);

        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, "activeResponse", 401);

        given()
            .header("Authorization", "Bearer oidc_opac_token")
        .when()
            .get("/api/themes/anything")
        .then()
            .statusCode(401);
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
              "given_name" : "%s",
              "family_name" : "%s"
            }""".formatted(emailClaimValue, firstname, lastname);

        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, activeResponse, 200);

        updateMockerServerSpecifications(INTROSPECT_TOKEN_URI_PATH, """
            {
                "exp": %d,
                "scope": "openid email profile",
                "client_id": "tcalendar",
                "active": true,
                "aud": "tcalendar",
                "sub": "twake-calendar-dev",
                "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
                "iss": "https://sso.linagora.com"
              }
              """.formatted(Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond()), 200);

        given()
            .header("Authorization", "Bearer " + "fakeToken")
        .when()
            .get("/api/themes/anything")
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
                "email": "%s"
            }""".formatted(emailClaimValue);

        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, activeResponse, 200);

        updateMockerServerSpecifications(INTROSPECT_TOKEN_URI_PATH, """
            {
                "exp": %d,
                "scope": "openid email profile",
                "client_id": "tcalendar",
                "active": true,
                "aud": "tcalendar",
                "sub": "twake-calendar-dev",
                "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
                "iss": "https://sso.linagora.com"
              }
              """.formatted(Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond()), 200);

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