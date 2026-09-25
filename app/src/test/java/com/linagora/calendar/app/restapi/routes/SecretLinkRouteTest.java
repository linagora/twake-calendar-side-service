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

import static io.restassured.RestAssured.when;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static io.restassured.http.ContentType.JSON;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Supplier;

import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class SecretLinkRouteTest {

    private static final String PASSWORD = "secret";
    private static final String SECRET_LINK_BASE_URL = "https://mocked.url/xyz";

    private static final RestApiConfiguration initialRestApiConfiguration = RestApiConfiguration.builder()
        .enableBasicAuth(Optional.of(true))
        .adminPassword(Optional.of("secret"))
        .build();
    private static final RestApiConfiguration spyRestApiConfiguration = Mockito.spy(initialRestApiConfiguration);

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

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

    private OpenPaaSId openPaaSId;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        OpenPaaSUser user = sabreDavExtension.newTestUser();
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(user.username().getDomainPart().get());
        calendarDataProbe.addUserToRepository(user.username(), PASSWORD);
        openPaaSId = user.id();

        PreemptiveBasicAuthScheme basicAuthScheme = new PreemptiveBasicAuthScheme();
        basicAuthScheme.setUserName(user.username().asString());
        basicAuthScheme.setPassword(PASSWORD);

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setBasePath("")
            .setAuth(basicAuthScheme)
            .build();
    }

    private String getPath(String calendarId) {
        return String.format("/calendar/api/calendars/%s/%s/secret-link", calendarId, calendarId);
    }

    @Test
    void shouldReturnValidSecretLinkResponseWhenRequestIsValid() {
        String response = when()
            .get(getPath(openPaaSId.value()))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
                { "secretLink": "${json-unit.ignore}" }""");
    }

    @Test
    void shouldReturnSecretLinkValueWithExpectedFormat() {
        String pairCalendarId = openPaaSId.value() + "/" + openPaaSId.value();
        String secretLink = when()
            .get(String.format("/calendar/api/calendars/%s/secret-link", pairCalendarId))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .jsonPath()
            .getString("secretLink");

        assertThat(secretLink).startsWith(SECRET_LINK_BASE_URL + "/api/calendars/" + pairCalendarId + "/calendar.ics?token=");
    }

    @Test
    void shouldReturnForbiddenWhenCalendarIsNotReadable() {
        when()
            .get("/calendar/api/calendars/123/456/secret-link")
        .then()
            .statusCode(HttpStatus.SC_FORBIDDEN);
    }

    @Test
    void shouldReturnNewSecretLinkWhenShouldResetLinkParamIsTrue() {
        Supplier<String> secretLinkSupplier = () -> when()
            .get(getPath(openPaaSId.value()) + "?shouldResetLink=true")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .jsonPath()
            .getString("secretLink");

        String secretLinkFirst = secretLinkSupplier.get();
        String secretLinkSecond = secretLinkSupplier.get();

        assertThat(secretLinkFirst)
            .isNotEqualTo(secretLinkSecond);
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "not_a_boolean"})
    void shouldReturnSameSecretLinkWhenShouldResetLinkParamIsNotTrue(String shouldResetLink) {
        Supplier<String> secretLinkSupplier = () -> when()
            .get(getPath(openPaaSId.value()) + "?shouldResetLink=" + shouldResetLink)
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .jsonPath()
            .getString("secretLink");

        String secretLinkFirst = secretLinkSupplier.get();

        for (int i = 0; i < 3; i++) {
            assertThat(secretLinkSupplier.get())
                .isEqualTo(secretLinkFirst);
        }
    }

    @Test
    void shouldReturnSameSecretLinkWhenShouldResetLinkParamIsNotProvided() {
        Supplier<String> secretLinkSupplier = () -> when()
            .get(getPath(openPaaSId.value()))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .jsonPath()
            .getString("secretLink");

        String secretLinkFirst = secretLinkSupplier.get();

        for (int i = 0; i < 3; i++) {
            assertThat(secretLinkSupplier.get())
                .isEqualTo(secretLinkFirst);
        }
    }
}
