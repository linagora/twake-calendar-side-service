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
import static org.hamcrest.Matchers.nullValue;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
import com.linagora.calendar.app.restapi.routes.PeopleSearchRouteTest.ResourceProbe;
import com.linagora.calendar.app.restapi.routes.PeopleSearchRouteTest.TeamCalendarProbe;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.TeamCalendar;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;
import net.javacrumbs.jsonunit.core.Option;

public class EntityRouteTest {
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
                .addBinding().to(ResourceProbe.class);
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
    private Resource resource;
    private TeamCalendar teamCalendar;
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

        resource = server.getProbe(ResourceProbe.class)
            .save(openPaaSUser, "meeting-room", "room", List.of(openPaaSUser.id(), openPaaSUser2.id()));
        teamCalendar = server.getProbe(TeamCalendarProbe.class)
            .save(domain, "engineering", "Engineering Team");

        restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();

        RestAssured.requestSpecification = buildRequestSpec(openPaaSUser.username().asString(), DEFAULT_USER_PASSWORD, restApiPort);

        server.getProbe(DomainAdminProbe.class)
            .addAdmin(resource.domain(), openPaaSUser.id());
        server.getProbe(DomainAdminProbe.class)
            .addAdmin(resource.domain(), openPaaSUser2.id());
    }

    @Test
    void shouldReturnResourceWrappedEntity() {
        String actualResponse = given()
            .when()
            .get(String.format("/api/entity/%s", resource.id().value()))
        .then()
            .statusCode(200)
            .contentType(JSON)
            .extract()
            .body().asString();

        assertThatJson(actualResponse)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                {
                    "resource": {
                        "timestamps": {
                            "creation": "${json-unit.ignore}",
                            "updatedAt": "${json-unit.ignore}"
                        },
                        "deleted": false,
                        "_id": "{resourceId}",
                        "name": "meeting-room",
                        "description": "meeting-room description",
                        "type": "resource",
                        "icon": "room",
                        "administrators": [
                            {
                                "id": "{adminId1}",
                                "objectType": "user",
                                "_id": "{adminId1}"
                            },
                            {
                                "id": "{adminId2}",
                                "objectType": "user",
                                "_id": "{adminId2}"
                            }
                        ],
                        "creator": "{creatorId}",
                        "domain": {
                            "name": "open-paas.org",
                            "_id": "{domainId}",
                            "administrators": [
                                {
                                    "user_id": "{domainAdminId1}",
                                    "timestamps": {
                                        "creation": "1970-01-01T00:00:00.000Z"
                                    }
                                },
                                {
                                    "user_id": "{domainAdminId2}",
                                    "timestamps": {
                                        "creation": "1970-01-01T00:00:00.000Z"
                                    }
                                }
                            ],
                            "timestamps": {
                                "creation": "${json-unit.ignore}"
                            },
                            "hostnames": [
                                "open-paas.org"
                            ],
                            "company_name": "open-paas.org"
                        }
                    }
                }
                """.replace("{resourceId}", resource.id().value())
                .replace("{adminId1}", openPaaSUser.id().value())
                .replace("{adminId2}", openPaaSUser2.id().value())
                .replace("{domainAdminId1}", openPaaSUser.id().value())
                .replace("{domainAdminId2}", openPaaSUser2.id().value())
                .replace("{creatorId}", openPaaSUser.id().value())
                .replace("{domainId}", resource.domain().value()));
    }

    @Test
    void shouldReturnUserWrappedEntity() {
        given()
            .when()
            .get(String.format("/api/entity/%s", openPaaSUser.id().value()))
        .then()
            .statusCode(200)
            .contentType(JSON)
            .body("user._id", equalTo(openPaaSUser.id().value()))
            .body("user.preferredEmail", equalTo(openPaaSUser.username().asString()))
            .body("user.objectType", equalTo("user"))
            .body("resource", nullValue())
            .body("domain", nullValue())
            .body("teamCalendar", nullValue());
    }

    @Test
    void shouldReturnDomainWrappedEntity() {
        given()
            .when()
            .get(String.format("/api/entity/%s", resource.domain().value()))
        .then()
            .statusCode(200)
            .contentType(JSON)
            .body("domain._id", equalTo(resource.domain().value()))
            .body("domain.name", equalTo(domain.asString()))
            .body("user", nullValue())
            .body("resource", nullValue())
            .body("teamCalendar", nullValue());
    }

    @Test
    void shouldReturnTeamCalendarWrappedEntity() {
        given()
            .when()
            .get(String.format("/api/entity/%s", teamCalendar.id().value()))
        .then()
            .statusCode(200)
            .contentType(JSON)
            .body("teamCalendar._id", equalTo(teamCalendar.id().value()))
            .body("teamCalendar.name", equalTo(teamCalendar.name()))
            .body("teamCalendar.displayName", equalTo(teamCalendar.displayName()))
            .body("user", nullValue())
            .body("resource", nullValue())
            .body("domain", nullValue());
    }

    @Test
    void shouldReturn404WhenEntityNotFound() {
        String unknownId = new ObjectId().toHexString();

        given()
            .when()
            .get(String.format("/api/entity/%s", unknownId))
        .then()
            .statusCode(404);
    }

    @Test
    void shouldReturn404WhenAccessingEntityFromAnotherDomain(TwakeCalendarGuiceServer server) {
        Domain otherDomain = Domain.of("domain999.tld");
        Username otherDomainUser = Username.fromLocalPartWithDomain(UUID.randomUUID().toString(), otherDomain);
        server.getProbe(CalendarDataProbe.class)
            .addDomain(otherDomain)
            .addUserToRepository(otherDomainUser, DEFAULT_USER_PASSWORD);

        given(buildRequestSpec(otherDomainUser.asString(), DEFAULT_USER_PASSWORD, restApiPort))
            .when()
            .get(String.format("/api/entity/%s", resource.id().value()))
        .then()
            .statusCode(404);
    }

    @Test
    void shouldReturn404WhenAccessingUserFromAnotherDomain(TwakeCalendarGuiceServer server) {
        OpenPaaSUser otherDomainUser = addUser(server, randomDomain());

        given()
            .when()
            .get(String.format("/api/entity/%s", otherDomainUser.id().value()))
        .then()
            .statusCode(404);
    }

    @Test
    void shouldReturn404WhenAccessingTeamCalendarFromAnotherDomain(TwakeCalendarGuiceServer server) {
        Domain otherDomain = randomDomain();
        server.getProbe(CalendarDataProbe.class)
            .addDomain(otherDomain);
        TeamCalendar otherDomainTeamCalendar = server.getProbe(TeamCalendarProbe.class)
            .save(otherDomain, "support", "Support Team");

        given()
            .when()
            .get(String.format("/api/entity/%s", otherDomainTeamCalendar.id().value()))
        .then()
            .statusCode(404);
    }

    @Test
    void shouldReturn404WhenAccessingDomainFromAnotherDomain(TwakeCalendarGuiceServer server) {
        OpenPaaSId otherDomainId = addDomain(server, randomDomain());

        given()
            .when()
            .get(String.format("/api/entity/%s", otherDomainId.value()))
        .then()
            .statusCode(404);
    }

    @Test
    void adminShouldAccessUserFromAnotherDomain() {
        given(buildRequestSpec(ADMIN, ADMIN_PASSWORD, restApiPort))
            .when()
            .get(String.format("/api/entity/%s", openPaaSUser.id().value()))
        .then()
            .statusCode(200)
            .contentType(JSON)
            .body("user._id", equalTo(openPaaSUser.id().value()))
            .body("user.preferredEmail", equalTo(openPaaSUser.username().asString()));
    }

    @Test
    void adminShouldAccessTeamCalendarFromAnotherDomain() {
        given(buildRequestSpec(ADMIN, ADMIN_PASSWORD, restApiPort))
            .when()
            .get(String.format("/api/entity/%s", teamCalendar.id().value()))
        .then()
            .statusCode(200)
            .contentType(JSON)
            .body("teamCalendar._id", equalTo(teamCalendar.id().value()))
            .body("teamCalendar.name", equalTo(teamCalendar.name()));
    }

    @Test
    void adminShouldAccessResourceFromAnotherDomain() {
        given(buildRequestSpec(ADMIN, ADMIN_PASSWORD, restApiPort))
            .when()
            .get(String.format("/api/entity/%s", resource.id().value()))
        .then()
            .statusCode(200)
            .contentType(JSON)
            .body("resource._id", equalTo(resource.id().value()))
            .body("resource.name", equalTo(resource.name()));
    }

    @Test
    void adminShouldAccessDomainFromAnotherDomain() {
        given(buildRequestSpec(ADMIN, ADMIN_PASSWORD, restApiPort))
            .when()
            .get(String.format("/api/entity/%s", resource.domain().value()))
        .then()
            .statusCode(200)
            .contentType(JSON)
            .body("domain._id", equalTo(resource.domain().value()))
            .body("domain.name", equalTo(domain.asString()));
    }

    @Test
    void shouldReturn401WhenNoAuthenticationProvided() {
        String fakePassword = UUID.randomUUID().toString();

        given(buildRequestSpec(openPaaSUser.username().asString(), fakePassword, restApiPort))
        .when()
            .get(String.format("/api/entity/%s", resource.id().value()))
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

    private OpenPaaSUser addUser(TwakeCalendarGuiceServer server, Domain domain) {
        Username username = Username.fromLocalPartWithDomain(UUID.randomUUID().toString(), domain);
        OpenPaaSId id = server.getProbe(CalendarDataProbe.class)
            .addDomain(domain)
            .addUser(username, DEFAULT_USER_PASSWORD);
        return new OpenPaaSUser(username, id, "", "");
    }

    private OpenPaaSId addDomain(TwakeCalendarGuiceServer server, Domain domain) {
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class)
            .addDomain(domain);
        return calendarDataProbe.domainId(domain);
    }

    private Domain randomDomain() {
        return Domain.of("domain-" + UUID.randomUUID() + ".tld");
    }
}
