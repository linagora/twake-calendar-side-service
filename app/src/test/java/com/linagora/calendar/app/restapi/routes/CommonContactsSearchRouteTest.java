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
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.james.backends.rabbitmq.RabbitMQExtension.IsolationPolicy.WEAK;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import org.apache.http.HttpStatus;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.app.modules.MemoryAutoCompleteModule;
import com.linagora.calendar.commoncontacts.api.CommonContactsApiConfiguration;
import com.linagora.calendar.commoncontacts.api.CommonContactsApiConfiguration.Secret;
import com.linagora.calendar.commoncontacts.api.CommonContactsApiServerProbe;
import com.linagora.calendar.restapi.RestApiServerProbe;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import net.javacrumbs.jsonunit.core.Option;

class CommonContactsSearchRouteTest {
    private static final String DOMAIN = "open-paas.ltd";
    private static final String PASSWORD = "secret";
    private static final String SECRET_1 = "abcdef";
    private static final String SECRET_2 = "ghijz";
    private static final Username BOB = Username.fromLocalPartWithDomain("bob", DOMAIN);
    private static final Username ALICE = Username.fromLocalPartWithDomain("alice", DOMAIN);

    @RegisterExtension
    @Order(1)
    private static final RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(WEAK);

    @RegisterExtension
    @Order(2)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        AppTestHelper.BY_PASS_MODULE.apply(rabbitMQExtension),
        binder -> binder.bind(CommonContactsApiConfiguration.class)
            .toInstance(new CommonContactsApiConfiguration(Optional.of(CommonContactsApiConfiguration.RANDOM_PORT),
                Set.of(new Secret(SECRET_1), new Secret(SECRET_2)))));

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        server.getProbe(CalendarDataProbe.class).addDomain(Domain.of(DOMAIN));
        server.getProbe(CalendarDataProbe.class).addUser(BOB, PASSWORD);
        server.getProbe(CalendarDataProbe.class).addUser(ALICE, PASSWORD);

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(CommonContactsApiServerProbe.class).getPort().getValue())
            .setBasePath("/api/people/search")
            .addHeader("Authorization", "Bearer " + SECRET_1)
            .build();
    }

    private void addContact(TwakeCalendarGuiceServer server, Username owner, String email, String firstName, String lastName) {
        server.getProbe(MemoryAutoCompleteModule.Probe.class)
            .add(owner.asString(), email, firstName, lastName);
    }

    @Test
    void shouldReturnMatchingContacts(TwakeCalendarGuiceServer server) {
        addContact(server, BOB, "naruto@domain.tld", "naruto", "hokage");
        addContact(server, BOB, "sasuke@domain.tld", "sasuke", "uchiha");
        addContact(server, BOB, "sasuke-clone@domain.tld", "sasuke", "clone");

        String response = given()
            .body("""
                {
                  "user": "bob@open-paas.ltd",
                  "q": "sasuke",
                  "objectTypes": [ "contact" ],
                  "limit": 10,
                  "offset": 0
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
                [
                  {
                    id: "${json-unit.ignore}",
                    objectType: "contact",
                    names: [ { displayName: "sasuke clone", type: "default" } ],
                    emailAddresses: [ { value: "sasuke-clone@domain.tld", type: "Work" } ]
                  },
                  {
                    id: "${json-unit.ignore}",
                    objectType: "contact",
                    names: [ { displayName: "sasuke uchiha", type: "default" } ],
                    emailAddresses: [ { value: "sasuke@domain.tld", type: "Work" } ]
                  }
                ]""");
    }

    @Test
    void shouldAcceptEveryConfiguredSecret(TwakeCalendarGuiceServer server) {
        addContact(server, BOB, "sasuke@domain.tld", "sasuke", "uchiha");

        String response = given()
            .header("Authorization", "Bearer " + SECRET_2)
            .body("""
                {
                  "user": "bob@open-paas.ltd",
                  "q": "sasuke",
                  "objectTypes": [ "contact" ],
                  "limit": 10
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(response).isArray().hasSize(1);
    }

    @Test
    void shouldOnlyReturnContactsOfTheRequestedUser(TwakeCalendarGuiceServer server) {
        addContact(server, BOB, "sasuke@domain.tld", "sasuke", "uchiha");
        addContact(server, ALICE, "sakura@domain.tld", "sakura", "haruno");

        String response = given()
            .body("""
                {
                  "user": "alice@open-paas.ltd",
                  "q": "sa",
                  "objectTypes": [ "contact" ],
                  "limit": 10
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
                [
                  {
                    id: "${json-unit.ignore}",
                    objectType: "contact",
                    names: [ { displayName: "sakura haruno", type: "default" } ],
                    emailAddresses: [ { value: "sakura@domain.tld", type: "Work" } ]
                  }
                ]""");
    }

    @Test
    void shouldApplyOffsetAndLimit(TwakeCalendarGuiceServer server) {
        addContact(server, BOB, "sasuke1@domain.tld", "sasuke", "a");
        addContact(server, BOB, "sasuke2@domain.tld", "sasuke", "b");
        addContact(server, BOB, "sasuke3@domain.tld", "sasuke", "c");

        String response = given()
            .body("""
                {
                  "user": "bob@open-paas.ltd",
                  "q": "sasuke",
                  "objectTypes": [ "contact" ],
                  "limit": 1,
                  "offset": 1
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .withOptions(Option.IGNORING_EXTRA_FIELDS)
            .isEqualTo("""
                [
                  { emailAddresses: [ { value: "sasuke2@domain.tld", type: "Work" } ] }
                ]""");
    }

    @Test
    void shouldSearchContactsWhenObjectTypesAreOmitted(TwakeCalendarGuiceServer server) {
        addContact(server, BOB, "sasuke@domain.tld", "sasuke", "uchiha");

        String response = given()
            .body("""
                {
                  "user": "bob@open-paas.ltd",
                  "q": "sasuke",
                  "limit": 10
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .withOptions(Option.IGNORING_EXTRA_FIELDS)
            .isEqualTo("""
                [
                  { emailAddresses: [ { value: "sasuke@domain.tld", type: "Work" } ] }
                ]""");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"user\"", "\"resource\"", "\"team-calendar\"", "\"unknown\"", "\"contact\", \"user\""})
    void shouldRejectUnsupportedObjectTypes(String objectTypes) {
        given()
            .body("""
                {
                  "user": "bob@open-paas.ltd",
                  "q": "sasuke",
                  "objectTypes": [ %s ],
                  "limit": 10
                }""".formatted(objectTypes))
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldRejectUserObjectTypeWithExplicitMessage() {
        given()
            .body("""
                {
                  "user": "bob@open-paas.ltd",
                  "q": "sasuke",
                  "objectTypes": [ "user" ],
                  "limit": 10
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Unsupported object type: 'user'. Supported: [contact]"));
    }

    @Test
    void shouldRejectRequestsWithoutAuthorization() {
        given()
            .header("Authorization", "")
            .body("""
                {
                  "user": "bob@open-paas.ltd",
                  "q": "sasuke",
                  "limit": 10
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_UNAUTHORIZED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer wrong", "Bearer ", "Bearer abcdefg", "Basic Ym9iQG9wZW4tcGFhcy5sdGQ6c2VjcmV0", "abcdef"})
    void shouldRejectInvalidAuthorization(String authorization) {
        given()
            .header("Authorization", authorization)
            .body("""
                {
                  "user": "bob@open-paas.ltd",
                  "q": "sasuke",
                  "limit": 10
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_UNAUTHORIZED);
    }

    @Test
    void shouldReturnNotFoundOnUnknownRoute() {
        given()
            .basePath("/api/people/unknown")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    void shouldNotExposeOtherRestApiRoutes() {
        given()
            .basePath("/api/user")
        .when()
            .get()
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    void secretsShouldNotGrantAccessToTheMainRestApi(TwakeCalendarGuiceServer server) {
        given()
            .port(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .body("""
                {
                  "q": "sasuke",
                  "limit": 10
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_UNAUTHORIZED);
    }

    @Test
    void shouldRejectMissingUser() {
        given()
            .body("""
                {
                  "q": "sasuke",
                  "limit": 10
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'user' is required"));
    }

    @Test
    void shouldRejectUserWithoutDomain() {
        given()
            .body("""
                {
                  "user": "bob",
                  "q": "sasuke",
                  "limit": 10
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 257})
    void shouldRejectInvalidLimit(int limit) {
        given()
            .body("""
                {
                  "user": "bob@open-paas.ltd",
                  "q": "sasuke",
                  "limit": %d
                }""".formatted(limit))
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 991})
    void shouldRejectInvalidOffset(int offset) {
        given()
            .body("""
                {
                  "user": "bob@open-paas.ltd",
                  "q": "sasuke",
                  "limit": 10,
                  "offset": %d
                }""".formatted(offset))
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldRejectMalformedBody() {
        given()
            .body("{ not json")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldRejectEmptyBody() {
        given()
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }
}
