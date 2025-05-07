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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import org.apache.http.HttpStatus;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import com.github.fge.lambdas.Throwing;
import com.google.inject.name.Names;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.OpenPaaSId;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

public class SecretLinkRouteTest {

    private static final String DOMAIN = "open-paas.ltd";
    private static final String PASSWORD = "secret";
    private static final String SECRET_LINK_BASE_URL = "https://mocked.url/xyz";
    private static final Username USERNAME = Username.fromLocalPartWithDomain("bob", DOMAIN);

    private static final RestApiConfiguration initialRestApiConfiguration = RestApiConfiguration.builder().build();
    private static final RestApiConfiguration spyRestApiConfiguration = Mockito.spy(initialRestApiConfiguration);

    @RegisterExtension
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        binder -> binder.bind(URL.class).annotatedWith(Names.named("userInfo"))
            .toProvider(() -> Throwing.supplier(() -> new URI("https://neven.to.be.called.com").toURL()).get()),
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
        server.getProbe(CalendarDataProbe.class).addDomain(Domain.of(DOMAIN));

        PreemptiveBasicAuthScheme basicAuthScheme = new PreemptiveBasicAuthScheme();
        basicAuthScheme.setUserName(USERNAME.asString());
        basicAuthScheme.setPassword(PASSWORD);

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setBasePath("")
            .setAuth(basicAuthScheme)
            .build();

        openPaaSId = server.getProbe(CalendarDataProbe.class)
            .addUser(USERNAME, PASSWORD);
    }

    private String getPath(String calendarId) {
        return String.format("/api/calendars/%s/%s/secret-link", calendarId, calendarId);
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

    @ParameterizedTest
    @ValueSource(strings = {"123/456", "111/111"})
    void shouldReturnSecretLinkValueWithExpectedFormat(String pairCalendarId) {
        String secretLink = when()
            .get(String.format("/api/calendars/%s/secret-link", pairCalendarId))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .jsonPath()
            .getString("secretLink");

        assertThat(secretLink).startsWith(SECRET_LINK_BASE_URL + "/calendars/" + pairCalendarId + "?token=");
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
