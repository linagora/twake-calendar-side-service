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

import static com.linagora.calendar.app.restapi.routes.ImportRouteTest.mailSenderConfigurationFunction;
import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RedirectConfig.redirectConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static io.restassured.http.ContentType.JSON;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.utils.GuiceProbe;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.DomainAdminProbe;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.app.restapi.routes.PeopleSearchRouteTest.TeamCalendarProbe;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.model.TeamCalendarId;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;

public class TeamCalendarRouteTest {
    private static final String DEFAULT_USER_PASSWORD = "secret";
    private static final String ADMIN = "admin@linagora.com";
    private static final String ADMIN_PASSWORD = "secret";

    @RegisterExtension
    @Order(1)
    static final MockSmtpServerExtension SMTP_EXTENSION = new MockSmtpServerExtension();

    @RegisterExtension
    @Order(2)
    static SabreDavExtension SABRE_DAV_EXTENSION = SabreDavExtension.shared();

    @RegisterExtension
    @Order(3)
    static final TwakeCalendarExtension TCALENDAR_EXTENSION = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(SABRE_DAV_EXTENSION),
        binder -> {
            Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding().to(TeamCalendarProbe.class);
            Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding().to(DomainAdminProbe.class);
            binder.bind(MailSenderConfiguration.class)
                .toInstance(mailSenderConfigurationFunction.apply(SMTP_EXTENSION));
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private OpenPaaSUser openPaaSUser;
    private OpenPaaSUser openPaaSUser2;
    private Domain domain;
    private TeamCalendar teamCalendar;
    private TeamCalendar teamCalendar2;
    private int restApiPort;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        openPaaSUser = SABRE_DAV_EXTENSION.newTestUser(Optional.of("openPaaSUser1"));
        openPaaSUser2 = SABRE_DAV_EXTENSION.newTestUser(Optional.of("openPaaSUser2"));

        server.getProbe(CalendarDataProbe.class)
            .addUserToRepository(openPaaSUser.username(), DEFAULT_USER_PASSWORD);
        server.getProbe(CalendarDataProbe.class)
            .addUserToRepository(openPaaSUser2.username(), DEFAULT_USER_PASSWORD);

        domain = openPaaSUser.username().getDomainPart().orElseThrow();

        teamCalendar = server.getProbe(TeamCalendarProbe.class)
            .save(domain, "engineering", "Engineering Team");
        teamCalendar2 = server.getProbe(TeamCalendarProbe.class)
            .save(domain, "marketing", "Marketing Team");

        restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();

        RestAssured.requestSpecification = buildRequestSpec(openPaaSUser.username().asString(), DEFAULT_USER_PASSWORD, restApiPort);

        server.getProbe(DomainAdminProbe.class)
            .addAdmin(teamCalendar.domain().id(), openPaaSUser.id());
        server.getProbe(DomainAdminProbe.class)
            .addAdmin(teamCalendar.domain().id(), openPaaSUser2.id());
    }

    @Test
    void shouldReturn200WhenTeamCalendarExists() {
        TeamCalendarId teamCalendarId = teamCalendar.id();

        String actualResponse = given()
            .when()
            .get(String.format("/api/team-calendars/%s", teamCalendarId.value()))
        .then()
            .statusCode(200)
            .contentType(JSON)
            .extract()
            .body().asString();

        assertThatJson(actualResponse)
            .isEqualTo("""
                {
                    "timestamps": {
                        "creation": "${json-unit.ignore}",
                        "updatedAt": "${json-unit.ignore}"
                    },
                    "_id": "{teamCalendarId}",
                    "name": "engineering",
                    "displayName": "Engineering Team",
                    "domain": {
                        "name": "open-paas.org",
                        "_id": "{domainId}",
                        "timestamps": {
                            "creation": "${json-unit.ignore}"
                        },
                        "hostnames": [
                            "open-paas.org"
                        ],
                        "company_name": "open-paas.org"
                    }
                }
                """.replace("{teamCalendarId}", teamCalendarId.value())
                .replace("{domainId}", teamCalendar.domain().id().value()));
    }

    @Test
    void shouldAllowAnyAuthenticatedUserToGetTeamCalendar() {
        given(buildRequestSpec(openPaaSUser2.username().asString(), DEFAULT_USER_PASSWORD, restApiPort))
            .when()
            .get(String.format("/api/team-calendars/%s", teamCalendar.id().value()))
        .then()
            .statusCode(200)
            .contentType(JSON)
            .body("_id", equalTo(teamCalendar.id().value()))
            .body("name", equalTo(teamCalendar.name()))
            .body("displayName", equalTo(teamCalendar.displayName()));
    }

    @Test
    void shouldReturn404WhenAccessingTeamCalendarFromAnotherDomain(TwakeCalendarGuiceServer server) {
        Domain otherDomain = Domain.of("domain999.tld");
        Username otherDomainUser = Username.fromLocalPartWithDomain(UUID.randomUUID().toString(), otherDomain);
        server.getProbe(CalendarDataProbe.class)
            .addDomain(otherDomain)
            .addUserToRepository(otherDomainUser, DEFAULT_USER_PASSWORD);

        given(buildRequestSpec(otherDomainUser.asString(), DEFAULT_USER_PASSWORD, restApiPort))
            .when()
            .get(String.format("/api/team-calendars/%s", teamCalendar.id().value()))
        .then()
            .statusCode(404);
    }

    @Test
    void adminShouldAccessTeamCalendarFromAnotherDomain() {
        given(buildRequestSpec(ADMIN, ADMIN_PASSWORD, restApiPort))
            .when()
            .get(String.format("/api/team-calendars/%s", teamCalendar.id().value()))
        .then()
            .statusCode(200)
            .contentType(JSON)
            .body("_id", equalTo(teamCalendar.id().value()))
            .body("name", equalTo(teamCalendar.name()));
    }

    @Test
    void shouldReturnDifferentDataForDifferentTeamCalendarIds() {
        String response1 = given()
            .when()
            .get(String.format("/api/team-calendars/%s", teamCalendar.id().value()))
        .then()
            .statusCode(200)
            .extract()
            .asString();

        String response2 = given()
            .when()
            .get(String.format("/api/team-calendars/%s", teamCalendar2.id().value()))
        .then()
            .statusCode(200)
            .extract()
            .asString();

        assertThatJson(response1).node("_id").isEqualTo(teamCalendar.id().value());
        assertThatJson(response1).node("name").isEqualTo(teamCalendar.name());

        assertThatJson(response2).node("_id").isEqualTo(teamCalendar2.id().value());
        assertThatJson(response2).node("name").isEqualTo(teamCalendar2.name());
    }

    @Test
    void shouldReturn404WhenTeamCalendarNotFound() {
        TeamCalendarId fakeTeamCalendarId = new TeamCalendarId(new ObjectId().toHexString());

        given()
            .when()
            .get(String.format("/api/team-calendars/%s", fakeTeamCalendarId.value()))
        .then()
            .statusCode(404);
    }

    @Test
    void shouldReturn401WhenNoAuthenticationProvided() {
        TeamCalendarId teamCalendarId = teamCalendar.id();

        String fakePassword = UUID.randomUUID().toString();

        given(buildRequestSpec(openPaaSUser.username().asString(), fakePassword, restApiPort))
        .when()
            .get(String.format("/api/team-calendars/%s", teamCalendarId.value()))
        .then()
            .statusCode(401);
    }

    private RequestSpecification buildRequestSpec(String username, String password, int port) {
        PreemptiveBasicAuthScheme auth = new PreemptiveBasicAuthScheme();
        auth.setUserName(username);
        auth.setPassword(password);

        return new RequestSpecBuilder()
            .setPort(port)
            .setAuth(auth)
            .setAccept(JSON)
            .setContentType(JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8))
                .redirect(redirectConfig().followRedirects(false)))
            .build();
    }
}
