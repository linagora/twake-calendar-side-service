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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.james.utils.GuiceProbe;
import org.assertj.core.api.SoftAssertions;
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
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceId;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;
import net.javacrumbs.jsonunit.core.Option;

public class ResourceRouteTest {
    private static final String DEFAULT_USER_PASSWORD = "secret";

    @RegisterExtension
    @Order(1)
    static final MockSmtpServerExtension SMTP_EXTENSION = new MockSmtpServerExtension();

    @RegisterExtension
    @Order(2)
    static SabreDavExtension SABRE_DAV_EXTENSION = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    @RegisterExtension
    @Order(3)
    static final TwakeCalendarExtension TCALENDAR_EXTENSION = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        AppTestHelper.EVENT_BUS_BY_PASS_MODULE,
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(SABRE_DAV_EXTENSION),
        binder -> {
            Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding().to(ResourceProbe.class);
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
    private Resource resource;
    private Resource resource2;
    private int restApiPort;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        openPaaSUser = SABRE_DAV_EXTENSION.newTestUser(Optional.of("openPaaSUser1"));
        openPaaSUser2 = SABRE_DAV_EXTENSION.newTestUser(Optional.of("openPaaSUser2"));

        server.getProbe(CalendarDataProbe.class)
            .addUserToRepository(openPaaSUser.username(), DEFAULT_USER_PASSWORD);
        server.getProbe(CalendarDataProbe.class)
            .addUserToRepository(openPaaSUser2.username(), DEFAULT_USER_PASSWORD);

        resource = server.getProbe(ResourceProbe.class)
            .save(openPaaSUser, "meeting-room", "room", List.of(openPaaSUser.id(), openPaaSUser2.id()));
        resource2 = server.getProbe(ResourceProbe.class)
            .save(openPaaSUser2, "Laptop HP", "laptop");

        restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();

        RestAssured.requestSpecification = buildRequestSpec(openPaaSUser.username().asString(), DEFAULT_USER_PASSWORD, restApiPort);

        server.getProbe(DomainAdminProbe.class)
            .addAdmin(resource.domain(), openPaaSUser2.id());
        server.getProbe(DomainAdminProbe.class)
            .addAdmin(resource.domain(), openPaaSUser.id());
    }

    @Test
    void shouldReturn200WhenResourceExists() {
        ResourceId resourceId = resource.id();

        String actualResponse = given()
            .when()
            .get(String.format("/linagora.esn.resource/api/resources/%s", resourceId.value()))
        .then()
            .statusCode(200)
            .contentType(JSON)
            .extract()
            .body().asString();

        assertThatJson(actualResponse)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                {
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
                        "schemaVersion": 1,
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
                        "injections": [],
                        "company_name": "open-paas.org",
                        "__v": 0
                    },
                    "__v": 0
                }
                """.replace("{resourceId}", resourceId.value())
                .replace("{adminId1}", openPaaSUser.id().value())
                .replace("{adminId2}", openPaaSUser2.id().value())
                .replace("{domainAdminId1}", openPaaSUser.id().value())
                .replace("{domainAdminId2}", openPaaSUser2.id().value())
                .replace("{creatorId}", openPaaSUser.id().value())
                .replace("{domainId}", resource.domain().value()));
    }

    @Test
    void shouldReturnResourceWithDeletedTrueWhenSoftDeleted(TwakeCalendarGuiceServer server) {
        ResourceId resourceId = server.getProbe(ResourceProbe.class).saveAndRemove(openPaaSUser, "mobile iphone", "mobile");

        given()
            .when()
            .get(String.format("/linagora.esn.resource/api/resources/%s", resourceId.value()))
        .then()
            .statusCode(200)
            .contentType(JSON)
            .body("_id", equalTo(resourceId.value()))
            .body("name", equalTo("mobile iphone"))
            .body("deleted", equalTo(true));
    }

    @Test
    void shouldAllowAnyAuthenticatedUserToGetResource(TwakeCalendarGuiceServer server) throws Exception {
        given(buildRequestSpec(openPaaSUser2.username().asString(), DEFAULT_USER_PASSWORD, restApiPort))
            .when()
            .get(String.format("/linagora.esn.resource/api/resources/%s", resource.id().value()))
        .then()
            .statusCode(200)
            .contentType(JSON)
            .body("_id", equalTo(resource.id().value()))
            .body("name", equalTo(resource.name()));
    }

    @Test
    void shouldReturnDifferentDataForDifferentResourceIds(TwakeCalendarGuiceServer server) throws Exception {
        // When: GET resource 1
        String response1 = given()
            .when()
            .get(String.format("/linagora.esn.resource/api/resources/%s", resource.id().value()))
        .then()
            .statusCode(200)
            .extract()
            .asString();

        // And: GET resource 2
        String response2 = given()
            .when()
            .get(String.format("/linagora.esn.resource/api/resources/%s", resource2.id().value()))
        .then()
            .statusCode(200)
            .extract()
            .asString();

        assertThatJson(response1).node("_id").isEqualTo(resource.id().value());
        assertThatJson(response1).node("name").isEqualTo(resource.name());

        assertThatJson(response2).node("_id").isEqualTo(resource2.id().value());
        assertThatJson(response2).node("name").isEqualTo(resource2.name());

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(resource.id().value()).isNotEqualTo(resource2.id().value());
            softly.assertThat(resource.name()).isNotEqualTo(resource2.name());
        });
    }

    @Test
    void shouldReturn404WhenResourceNotFound() {
        ResourceId fakeResourceId = new ResourceId(new ObjectId().toHexString());

        given()
            .when()
            .get(String.format("/linagora.esn.resource/api/resources/%s", fakeResourceId.value()))
        .then()
            .statusCode(404);
    }

    @Test
    void shouldReturn401WhenNoAuthenticationProvided() {
        ResourceId resourceId = resource.id();

        String fakePassword = UUID.randomUUID().toString();

        given(buildRequestSpec(openPaaSUser.username().asString(), fakePassword, restApiPort))
        .when()
            .get(String.format("/linagora.esn.resource/api/resources/%s", resourceId.value()))
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
