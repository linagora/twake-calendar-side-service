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

package com.linagora.calendar.app.oidc;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.apache.james.backends.redis.RedisConfiguration;
import org.apache.james.backends.redis.StandaloneRedisConfiguration;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.jwt.introspection.IntrospectionEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.verify.VerificationTimes;

import com.github.fge.lambdas.Throwing;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.redis.DockerRedisExtension;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

public class TwakeCalendarOidcAuthenticationRedisTest {

    private static final String DOMAIN = "open-paas.ltd";
    private static final Username USERNAME = Username.fromLocalPartWithDomain("user1", DOMAIN);
    private static final String USERINFO_TOKEN_URI_PATH = "/oauth2/userinfo";
    private static final String INTROSPECT_TOKEN_URI_PATH = "/oauth2/introspect";

    private static final ClientAndServer mockServer = ClientAndServer.startClientAndServer(0);

    private static URL getUserInfoTokenEndpoint() {
        return Throwing.supplier(() -> URI.create(String.format("http://127.0.0.1:%s%s", mockServer.getLocalPort(), USERINFO_TOKEN_URI_PATH)).toURL()).get();
    }
    private static URL getInrospectTokenEndpoint() {
        return Throwing.supplier(() -> URI.create(String.format("http://127.0.0.1:%s%s", mockServer.getLocalPort(), INTROSPECT_TOKEN_URI_PATH)).toURL()).get();
    }

    @Order(1)
    @RegisterExtension
    static DockerRedisExtension dockerExtension = new DockerRedisExtension();

    @Order(2)
    @RegisterExtension
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY)
            .oidcTokenStorageChoice(TwakeCalendarConfiguration.OIDCTokenStorageChoice.REDIS),
        binder -> binder.bind(URL.class).annotatedWith(Names.named("userInfo"))
            .toProvider(TwakeCalendarOidcAuthenticationRedisTest::getUserInfoTokenEndpoint),
        binder -> binder.bind(IntrospectionEndpoint.class)
            .toProvider(() -> new IntrospectionEndpoint(getInrospectTokenEndpoint(), Optional.empty())),
        new AbstractModule() {
            @Provides
            @Singleton
            public RedisConfiguration redisConfiguration() {
                return StandaloneRedisConfiguration.from(dockerExtension.redisURI().toString());
            }
        });

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

        server.getProbe(CalendarDataProbe.class)
            .addUser(USERNAME, "dummyPassword");

        updateMockServerUserInfoResponse(USERNAME.asString());
        updateMockServerIntrospectionResponse();
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

    private void updateMockServerUserInfoResponse(String emailClaimValue) {
        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, """
            {
              "sub": "twake-calendar-dev",
              "email": "%s",
              "family_name": "twake-calendar-dev",
              "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
              "name": "twake-calendar-dev"
            }""".formatted(emailClaimValue), 200);
    }

    private void updateMockServerIntrospectionResponse() {
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
              }""".formatted(Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond()), 200);
    }

    @Test
    void shouldAuthenticateSuccessfullyWithValidOidcToken() {
        given()
            .header("Authorization", "Bearer oidc_opac_token")
        .when()
            .get("/api/themes/anything")
        .then()
            .statusCode(200);
    }

    @Test
    void shouldUseCacheAfterFirstOidcTokenValidation() {
        for (int i = 0; i < 3; i++) {
            given()
                .header("Authorization", "Bearer oidc_opac_token")
            .when()
                .get("/api/themes/anything")
            .then()
                .statusCode(200);
        }

        // Verify that the mock server was called only once
        mockServer.verify(HttpRequest.request().withPath(USERINFO_TOKEN_URI_PATH),
            VerificationTimes.exactly(1));
    }

    @Test
    void shouldNotShareCacheAcrossDifferentOidcTokens() {
        String userInfoResponseTemplate = """
            {
              "sub": "twake-calendar-dev",
              "email": "%s",
              "family_name": "twake-calendar-dev",
              "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
              "name": "twake-calendar-dev"
            }""";

        String token1Email = "a@" + DOMAIN;
        String token1Response = userInfoResponseTemplate.formatted(token1Email);

        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, token1Response, 200);
        String token1 = "Bearer token1";

        given()
            .header("Authorization", token1)
        .when()
            .get("/api/themes/anything")
        .then()
            .statusCode(200);

        String token2Email = "b@" + DOMAIN;
        String token2Response = userInfoResponseTemplate.formatted(token2Email);
        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, token2Response, 200);

        String token2 = "Bearer token2";
        given()
            .header("Authorization", token2)
        .when()
            .get("/api/themes/anything")
        .then()
            .statusCode(200);

        mockServer.verify(
            HttpRequest.request()
                .withPath(USERINFO_TOKEN_URI_PATH)
                .withHeader("Authorization", token1),
            VerificationTimes.exactly(1));

        mockServer.verify(
            HttpRequest.request()
                .withPath(USERINFO_TOKEN_URI_PATH)
                .withHeader("Authorization", token2),
            VerificationTimes.exactly(1));
    }
}
