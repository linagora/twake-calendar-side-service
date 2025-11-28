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
import static io.restassured.http.ContentType.JSON;
import static org.apache.james.backends.rabbitmq.RabbitMQExtension.IsolationPolicy.WEAK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.verify.VerificationTimes.exactly;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.apache.http.HttpStatus;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.backends.redis.RedisConfiguration;
import org.apache.james.backends.redis.StandaloneRedisConfiguration;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.jwt.introspection.IntrospectionEndpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import com.github.fge.lambdas.Throwing;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.restapi.auth.LemonCookieAuthenticationStrategy;
import com.linagora.calendar.storage.redis.DockerRedisExtension;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

public class TwakeCalendarOidcFallbackCookieAuthenticationTest {

    private static final String DOMAIN = "open-paas.ltd";
    private static final Username USERNAME = Username.fromLocalPartWithDomain("user1", DOMAIN);
    private static final String USERINFO_TOKEN_URI_PATH = "/oauth2/userinfo";
    private static final String INTROSPECT_TOKEN_URI_PATH = "/oauth2/introspect";
    private static final String COOKIE_RESOLUTION_PATH = AppTestHelper.COOKIE_RESOLUTION_PATH;

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

    @RegisterExtension
    @Order(2)
    private static final RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(WEAK);

    private static LemonCookieAuthenticationStrategy.ResolutionConfiguration getResolutionConfiguration() {
        String resolutionURL = String.format("http://127.0.0.1:%s%s", mockServer.getLocalPort(), COOKIE_RESOLUTION_PATH);
        return new LemonCookieAuthenticationStrategy.ResolutionConfiguration(URI.create(resolutionURL),
            Domain.of(DOMAIN));
    }

    @Order(3)
    @RegisterExtension
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY)
            .enableRedis(),
        DavModuleTestHelper.BY_PASS_MODULE,
        DavModuleTestHelper.RABBITMQ_MODULE.apply(rabbitMQExtension),
        AppTestHelper.LEMON_COOKIE_AUTHENTICATION_STRATEGY_MODULE.apply(getResolutionConfiguration()),
        binder -> binder.bind(URL.class).annotatedWith(Names.named("userInfo"))
            .toProvider(TwakeCalendarOidcFallbackCookieAuthenticationTest::getUserInfoTokenEndpoint),
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

    @AfterAll
    static void tearDown() {
        mockServer.stop();
    }

    private void updateMockServerSpecifications(String path, String response, int statusResponse) {
        mockServer
            .when(HttpRequest.request().withPath(path))
            .respond(HttpResponse.response().withStatusCode(statusResponse)
                .withHeader("Content-Type", "application/json")
                .withBody(response, StandardCharsets.UTF_8));
    }

    private void updateMockServerUserInfoResponse(String emailClaimValue) {
        updateMockServerSpecifications(USERINFO_TOKEN_URI_PATH, """
            {
              "sub": "twake-calendar-dev",
              "email": "%s",
              "family_name": "twake-calendar-dev",
              "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
              "name": "twake-calendar-dev"
            }""".formatted(emailClaimValue), 200);
    }

    private void updateMockServerIntrospectionResponse() {
        updateMockServerSpecifications(INTROSPECT_TOKEN_URI_PATH, """
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
    void shouldAuthenticateWithCookieWhenBearerTokenIsAbsent() {
        String validLemonLdapCookie = "123";
        mockServer.when(HttpRequest.request()
                .withMethod("GET")
                .withPath(COOKIE_RESOLUTION_PATH)
                .withCookie("lemonldap", validLemonLdapCookie))
            .respond(HttpResponse.response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"result\":\"%s\"}".formatted(USERNAME.getLocalPart())));

        String preferredEmail = given()
            .cookie("lemonldap", validLemonLdapCookie)
        .when()
            .get("/api/user")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .jsonPath()
            .getString("preferredEmail");

        assertThat(preferredEmail)
            .isEqualTo(USERNAME.asString());
    }

    @Test
    void shouldFailAuthenticationWithInvalidLemonLdapCookie() {
        updateMockServerSpecifications(COOKIE_RESOLUTION_PATH, "{}", 401);

        given()
            .cookie("lemonldap", "invalidCookieValue")
        .when()
            .get("/api/user")
        .then()
            .statusCode(401);
    }

    @Test
    void shouldFailAuthenticationWhenLemonLdapCookieIsMissing() {
        updateMockServerSpecifications(COOKIE_RESOLUTION_PATH, "{}", 401);

        given()
            .cookie("notLemonLdap", "cookieValue")
        .when()
            .get("/api/user")
        .then()
            .statusCode(401);
    }

    @Test
    void shouldFailAuthenticationWhenNoCookieAndNoBearerToken() {
            given()
        .when()
            .get("/api/user")
        .then()
            .statusCode(401);
    }

    @Test
    void shouldPreferBearerTokenOverOtherAuthenticationMethods() {
        mockServer.when(HttpRequest.request()
                .withMethod("GET")
                .withPath(COOKIE_RESOLUTION_PATH))
            .respond(HttpResponse.response()
                .withStatusCode(401));

        given()
            .header("Authorization", "Bearer oidc_opac_token")
            .cookie("lemonldap", "123")
        .when()
            .get("/api/user")
        .then()
            .statusCode(200);

        mockServer.verify(HttpRequest.request()
                .withMethod("GET")
                .withPath(COOKIE_RESOLUTION_PATH),
            exactly(0));
    }

    @Test
    void shouldFailAuthenticationWhenBearerTokenIsInvalidEvenIfLemonLdapCookieIsValid() {
        mockServer.reset();
        updateMockServerSpecifications(INTROSPECT_TOKEN_URI_PATH, "{}", 401);
        updateMockServerSpecifications(USERINFO_TOKEN_URI_PATH, "{}", 401);

        mockServer.when(HttpRequest.request()
                .withMethod("GET")
                .withPath(COOKIE_RESOLUTION_PATH))
            .respond(HttpResponse.response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"result\":\"%s\"}".formatted(USERNAME.getLocalPart())));

        given()
            .header("Authorization", "Bearer invalidToken")
            .cookie("lemonldap", "validLemonLdapCookie")
        .when()
            .get("/api/user")
        .then()
            .statusCode(401);
    }

    @Test
    void shouldFailAuthenticationWhenLemonLdapResolutionReturnsMalformedJson() {
        String validLemonLdapCookie = "123";

        mockServer.when(HttpRequest.request()
                .withMethod("GET")
                .withPath(COOKIE_RESOLUTION_PATH)
                .withCookie("lemonldap", validLemonLdapCookie))
            .respond(HttpResponse.response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody("this-is-not-json"));

        given()
            .cookie("lemonldap", validLemonLdapCookie)
        .when()
            .get("/api/user")
        .then()
            .statusCode(401);
    }

}
