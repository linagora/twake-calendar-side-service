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

package com.linagora.calendar.app.restapi.routes;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static io.restassured.http.ContentType.JSON;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.http.HttpStatus;
import org.apache.james.core.Username;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;

import io.netty.handler.codec.http.QueryStringDecoder;
import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

public class DownloadCalendarRouteTest {

    private static final String PASSWORD = "secret";
    private static final String SECRET_LINK_BASE_URL = "https://mocked.url/xyz";

    private static final RestApiConfiguration initialRestApiConfiguration = RestApiConfiguration.builder().adminPassword(Optional.of("secret")).build();
    private static final RestApiConfiguration spyRestApiConfiguration = Mockito.spy(initialRestApiConfiguration);

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    @RegisterExtension
    @Order(2)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        binder -> {
            Mockito.doReturn(Throwing.supplier(() -> URI.create(SECRET_LINK_BASE_URL).toURL()).get())
                .when(spyRestApiConfiguration).getSelfUrl();
            binder.bind(RestApiConfiguration.class).toInstance(spyRestApiConfiguration);
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private OpenPaaSUser openPaaSUser;

    private int restApiPort;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        this.openPaaSUser = sabreDavExtension.newTestUser();

        server.getProbe(CalendarDataProbe.class).addDomain(openPaaSUser.username().getDomainPart().get());
        server.getProbe(CalendarDataProbe.class).addUserToRepository(openPaaSUser.username(), PASSWORD);

        PreemptiveBasicAuthScheme basicAuthScheme = new PreemptiveBasicAuthScheme();
        basicAuthScheme.setUserName(openPaaSUser.username().asString());
        basicAuthScheme.setPassword(PASSWORD);

        restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(restApiPort)
            .setBasePath("")
            .setAuth(basicAuthScheme)
            .build();
    }

    @Test
    void downloadShouldSucceedWhenValidToken() {
        String secretLinkUrl = getSecretLink();

        Response response = RestAssured
            .given()
        .when()
            .get(secretLinkUrl)
        .then()
            .statusCode(200)
            .extract()
            .response();

        String body = StringUtils.trim(response.getBody().asString());

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(response.getHeader("Content-Type")).isEqualTo("text/calendar; charset=utf-8");
            softly.assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=calendar.ics");
            softly.assertThat(body).startsWith("BEGIN:VCALENDAR");
            softly.assertThat(body).endsWith("END:VCALENDAR");
        }));
    }

    @Test
    void downloadShouldFailWhenInvalidToken() {
        String invalidSecretLinkUrl = String.format("http://localhost:%d/api/calendars/%s/%s/calendar.ics?token=invalid-token",
            restApiPort,
            openPaaSUser.id().value(),
            openPaaSUser.id().value());

        String response = RestAssured
            .given()
        .when()
            .get(invalidSecretLinkUrl)
        .then()
            .statusCode(HttpStatus.SC_FORBIDDEN)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
            {
                "error": {
                    "code": 403,
                    "message": "Forbidden",
                    "details": "Forbidden"
                }
            }
            """);
    }

    @Test
    void downloadShouldFailWhenMissingToken() {
        String urlMissingToken = String.format("http://localhost:%d/api/calendars/%s/%s/calendar.ics",
            restApiPort,
            openPaaSUser.id().value(),
            openPaaSUser.id().value());

        String response = RestAssured
            .given()
        .when()
            .get(urlMissingToken)
        .then()
            .statusCode(HttpStatus.SC_FORBIDDEN)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
        {
            "error": {
                "code": 403,
                "message": "Forbidden",
                "details": "Forbidden"
            }
        }
        """);
    }

    @Test
    void downloadShouldFailWhenTokenContainsSpecialCharacters() {
        String specialToken = "abc$#@!%^&*()";
        String urlWithSpecialToken = String.format(
            "http://localhost:%d/api/calendars/%s/%s/calendar.ics?token=%s",
            restApiPort,
            openPaaSUser.id().value(),
            openPaaSUser.id().value(),
            URLEncoder.encode(specialToken, StandardCharsets.UTF_8)
        );

        String response = RestAssured
            .given()
        .when()
            .get(urlWithSpecialToken)
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
        {
            "error": {
                "code": 400,
                "message": "Bad request",
                "details": "Invalid token: only letters, digits, hyphen, and underscore are allowed."
            }
        }
        """);
    }

    @Test
    void downloadShouldFailWhenTokenDoesNotMatchCalendarId(TwakeCalendarGuiceServer server) {
        String validTokenUrlForCalendarA = getSecretLink();

        String token = new QueryStringDecoder(URI.create(validTokenUrlForCalendarA)).parameters().getOrDefault("token", List.of())
            .stream()
            .findAny().get();

        OpenPaaSUser otherUser = sabreDavExtension.newTestUser();
        server.getProbe(CalendarDataProbe.class).addUserToRepository(otherUser.username(), "dummy");

        String otherCalendarId = otherUser.id().value();

        String invalidUrl = String.format(
            "http://localhost:%d/api/calendars/%s/%s/calendar.ics?token=%s",
            restApiPort,
            otherCalendarId,
            otherCalendarId,
            token);

        String response = RestAssured
            .given()
        .when()
            .get(invalidUrl)
        .then()
            .statusCode(HttpStatus.SC_FORBIDDEN)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
        {
            "error": {
                "code": 403,
                "message": "Forbidden",
                "details": "Forbidden"
            }
        }
        """);
    }

    @Test
    void downloadShouldFailWhenDavServerFails(TwakeCalendarGuiceServer server) {
        // New user setup does not correctly on the DAV server.
        Username newUser = Username.fromLocalPartWithDomain(UUID.randomUUID().toString(), openPaaSUser.username().getDomainPart().get());

        OpenPaaSId openPaaSId = server.getProbe(CalendarDataProbe.class).addUser(newUser, PASSWORD);

        String secretLink = getSecretLink(newUser.asString(), PASSWORD, openPaaSId.value());

        String response = RestAssured
            .given()
        .when()
            .get(secretLink)
        .then()
            .statusCode(HttpStatus.SC_SERVICE_UNAVAILABLE)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "error": {
                        "code": 503,
                        "message": "Service Unavailable",
                        "details": "Service Unavailable"
                    }
                }""");
    }

    private String getSecretLink() {
        return getSecretLink(openPaaSUser.username().asString(), PASSWORD, openPaaSUser.id().value());
    }

    private String getSecretLink(String username, String password, String calendarId) {
        PreemptiveBasicAuthScheme basicAuthScheme = new PreemptiveBasicAuthScheme();
        basicAuthScheme.setUserName(username);
        basicAuthScheme.setPassword(password);

        String secretLink = given(new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(restApiPort)
            .setBasePath("")
            .setAuth(basicAuthScheme)
            .build())
            .get(String.format("/calendar/api/calendars/%s/%s/secret-link", calendarId, calendarId))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .jsonPath()
            .getString("secretLink");

        return Strings.CS.replace(secretLink, SECRET_LINK_BASE_URL, "http://localhost:" + restApiPort);
    }

}
