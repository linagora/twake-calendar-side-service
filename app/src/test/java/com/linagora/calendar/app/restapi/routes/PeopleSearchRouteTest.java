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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.http.HttpStatus;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.utils.GuiceProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.app.modules.MemoryAutoCompleteModule;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceAdministrator;
import com.linagora.calendar.storage.model.ResourceId;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import net.javacrumbs.jsonunit.core.Option;

class PeopleSearchRouteTest {

    static class ResourceProbe implements GuiceProbe {
        private final ResourceDAO resourceDAO;
        private final OpenPaaSDomainDAO domainDAO;

        @Inject
        ResourceProbe(ResourceDAO resourceDAO, OpenPaaSDomainDAO domainDAO) {
            this.resourceDAO = resourceDAO;
            this.domainDAO = domainDAO;
        }

        public Resource save(OpenPaaSUser requestUser, String name, String icon) {
            return save(requestUser, name, icon, List.of(requestUser.id()));
        }

        public Resource save(OpenPaaSUser requestUser, String name, String icon, List<OpenPaaSId> adminIds) {
            ResourceInsertRequest insertRequest = buildInsertRequest(requestUser, name, icon, adminIds);

            return resourceDAO.insert(insertRequest)
                .flatMap(resourceDAO::findById)
                .block();
        }

        public ResourceId saveAndRemove(OpenPaaSUser requestUser, String name, String icon) {
            ResourceInsertRequest insertRequest = buildInsertRequest(requestUser, name, icon, List.of(requestUser.id()));

            return resourceDAO.insert(insertRequest)
                .flatMap(resourceId -> resourceDAO.softDelete(resourceId).thenReturn(resourceId))
                .block();
        }

        public List<Resource> listAll() {
            return resourceDAO.findAll().collectList().block();
        }

        private ResourceInsertRequest buildInsertRequest(OpenPaaSUser requestUser, String name, String icon, List<OpenPaaSId> adminIds) {
            List<ResourceAdministrator> administrators = adminIds.stream()
                .map(id -> new ResourceAdministrator(id, "user"))
                .toList();

            OpenPaaSDomain domain = domainDAO.retrieve(requestUser.username().getDomainPart().orElseThrow())
                .block();

            return new ResourceInsertRequest(administrators, requestUser.id(), name + " description", domain.id(), icon, name);
        }
    }

    private static final String DOMAIN = "open-paas.ltd";
    private static final Domain DISABLED_DOMAIN_1 = Domain.of("twake.app");
    private static final Domain DISABLED_DOMAIN_2 = Domain.of("xyz.com");
    private static final String PASSWORD = "secret";
    private static final Username USERNAME = Username.fromLocalPartWithDomain("bob", DOMAIN);

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
        binder -> {
            Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding().to(ResourceProbe.class);
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

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
            .setBasePath("/api/people/search")
            .setAuth(basicAuthScheme)
            .build();

        server.getProbe(CalendarDataProbe.class)
            .addUser(USERNAME, PASSWORD);
    }

    void addContact(TwakeCalendarGuiceServer server, String email, String firstName, String lastName) {
        server.getProbe(MemoryAutoCompleteModule.Probe.class)
            .add(USERNAME.asString(), email, firstName, lastName);
    }

    void addUser(TwakeCalendarGuiceServer server, Username username, String firstName, String lastName) {
        server.getProbe(CalendarDataProbe.class)
            .addUser(username, UUID.randomUUID().toString(), firstName, lastName);
    }

    @Test
    void shouldReturnAllMatchingContacts(TwakeCalendarGuiceServer server) {
        addContact(server, "naruto@domain.tld", "naruto", "hokage");
        addContact(server, "sasuke@domain.tld", "sasuke", "uchiha");
        addContact(server, "sasuke-clone@domain.tld", "sasuke", "clone");

        String response = given()
            .body("""
                {
                  "q" : "sasuke",
                  "objectTypes" : [ "user", "group", "contact", "ldap" ],
                  "limit" : 10
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                [
                  {
                    id: "${json-unit.ignore}",
                    objectType: "contact",
                    names: [ { displayName: "sasuke clone", type: "default" } ],
                    emailAddresses: [ { value: "sasuke-clone@domain.tld", type: "Work" } ],
                    phoneNumbers: [],
                    photos: [
                      {
                        url: "https://twcalendar.linagora.com/api/avatars?email=sasuke-clone@domain.tld",
                        type: "default"
                      }
                    ]
                  },
                  {
                    id: "${json-unit.ignore}",
                    objectType: "contact",
                    names: [ { displayName: "sasuke uchiha", type: "default" } ],
                    emailAddresses: [ { value: "sasuke@domain.tld", type: "Work" } ],
                    phoneNumbers: [],
                    photos: [
                      {
                        url: "https://twcalendar.linagora.com/api/avatars?email=sasuke@domain.tld",
                        type: "default"
                      }
                    ]
                  }
                ]""");
    }

    @Test
    void shouldReturnUserObjectTypeWhenUserTypeIsIncludedInFilter(TwakeCalendarGuiceServer server) {
        String username = "naruto@" + DOMAIN;
        addUser(server, Username.of(username), "naruto", "hokage");
        addContact(server, username, "naruto", "hokage");

        String response = given()
            .body("""
                {
                  "q" : "naruto",
                  "objectTypes" : [ "user", "group", "contact", "ldap" ],
                  "limit" : 10
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .whenIgnoringPaths("[0].id")
            .isEqualTo("""
                [
                    {
                        "id": "2f18b89b-f112-3c4c-9e9f-2cdbff80bd0e",
                        "objectType": "user",
                        "names": [
                            {
                                "displayName": "naruto hokage",
                                "type": "default"
                            }
                        ],
                        "emailAddresses": [
                            {
                                "value": "naruto@open-paas.ltd",
                                "type": "Work"
                            }
                        ],
                        "phoneNumbers": [],
                        "photos": [
                            {
                                "url": "https://twcalendar.linagora.com/api/avatars?email=naruto@open-paas.ltd",
                                "type": "default"
                            }
                        ]
                    }
                ]""");
    }

    @Test
    void shouldNotReturnUserObjectTypeWhenUserTypeIsExcludedInFilter(TwakeCalendarGuiceServer server) {
        String username = "naruto@" + DOMAIN;
        addUser(server, Username.of(username), "naruto", "hokage");
        addContact(server, username, "naruto", "hokage");

        String response = given()
            .body("""
                {
                  "q" : "naruto",
                  "objectTypes" : [ "contact" ],
                  "limit" : 10
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .inPath("[0].objectType")
            .isEqualTo("contact");
    }

    @Test
    void shouldReturnOnlyUserWhenFilterIsOnlyUser(TwakeCalendarGuiceServer server) {
        String username = "naruto@" + DOMAIN;
        addUser(server, Username.of(username), "naruto", "hokage");
        addContact(server, username, "naruto", "hokage");

        String response = given()
            .body("""
            {
              "q" : "naruto",
              "objectTypes" : [ "user" ],
              "limit" : 10
            }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isArray()
            .hasSize(1);

        assertThatJson(response)
            .inPath("[0].objectType")
            .isEqualTo("user");
    }

    @Test
    void shouldNotDropValidUsersWhenFilterIsOnlyUser(TwakeCalendarGuiceServer server) {
        // given:
        addContact(server, "contact1@domain.tld", "foo", "bar");
        addContact(server, "contact2@domain.tld", "baz", "qux");
        String validUserEmail = "naruto@" + DOMAIN;

        addUser(server, Username.of(validUserEmail), "naruto", "hokage");
        addContact(server, validUserEmail, "naruto", "hokage");

        // when:
        String response = given()
            .body("""
            {
              "q": "naruto",
              "objectTypes": ["user"],
              "limit": 1
            }
            """)
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isArray()
            .anySatisfy(json -> {
                assertThatJson(json).node("objectType").isEqualTo("user");
                assertThatJson(json).node("emailAddresses[0].value").isEqualTo(validUserEmail);
            });
    }

    @Test
    void shouldRespectLimitParameter(TwakeCalendarGuiceServer server) {
        addContact(server, "naruto@domain.tld", "naruto", "hokage");
        addContact(server, "sasuke@domain.tld", "sasuke", "uchiha");
        addContact(server, "sasuke-clone@domain.tld", "sasuke", "clone");

        String response = given()
            .body("""
            {
              "q" : "sasuke",
              "objectTypes" : [ "user", "group", "contact", "ldap" ],
              "limit" : 1
            }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isArray()
            .hasSize(1);
    }

    @Test
    void peopleSearchShouldIgnoreUnknownFields(TwakeCalendarGuiceServer server) {
        addContact(server, "naruto@domain.tld", "naruto", "hokage");

        String response = given()
            .body("""
                {
                  "q": "naruto",
                  "objectTypes": ["contact"],
                  "limit": 10,
                  "unknownField": "this_should_be_ignored"
                }
                """)
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isArray()
            .anySatisfy(json -> {
                assertThatJson(json)
                    .node("emailAddresses[0].value")
                    .isEqualTo("naruto@domain.tld");
            });
    }

    @Test
    void shouldReturnBadRequestWhenLimitExceedsMaxAllowed(TwakeCalendarGuiceServer server) {
        addUser(server, Username.fromLocalPartWithDomain("naruto", DOMAIN), "naruto", "hokage");

        given()
            .body("""
                {
                  "q": "naruto",
                  "objectTypes": ["user"],
                  "limit": 999
                }
                """)
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldReturnBadRequestWhenLimitIsNegative(TwakeCalendarGuiceServer server) {
        addUser(server, Username.fromLocalPartWithDomain("naruto", DOMAIN), "naruto", "hokage");

        given()
            .body("""
                {
                  "q": "naruto",
                  "objectTypes": ["user"],
                  "limit": -1
                }
                """)
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldReturnResourceWhenResourceTypeIncluded(TwakeCalendarGuiceServer server) {
        // given
        OpenPaaSUser openPaaSUser = server.getProbe(CalendarDataProbe.class).getUser(USERNAME);
        ResourceProbe resourceProbe = server.getProbe(ResourceProbe.class);
        resourceProbe.save(openPaaSUser, "meeting-room", "laptop");

        // when
        String response = given()
            .body("""
                {
                  "q" : "meeting",
                  "objectTypes" : [ "resource" ],
                  "limit" : 10
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        // then
        Resource firstResource = resourceProbe.listAll().getFirst();
        assertThatJson(response)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                [
                  {
                    "id": "%s",
                    "objectType": "resource",
                    "names": [ { "displayName": "meeting-room", "type": "default" } ],
                    "emailAddresses": [ { "value": "%s", "type": "default" } ],
                    "phoneNumbers": [],
                    "photos": [ { "url": "https://twcalendar.linagora.com/linagora.esn.resource/images/icon/laptop.svg", "type": "default" } ]
                  }
                ]""".formatted(firstResource.id().value(), firstResource.id().value() + "@" + DOMAIN));
    }

    @Test
    void shouldNotReturnDeletedResource(TwakeCalendarGuiceServer server) {
        // given
        OpenPaaSUser openPaaSUser = server.getProbe(CalendarDataProbe.class).getUser(USERNAME);
        ResourceProbe resourceProbe = server.getProbe(ResourceProbe.class);
        resourceProbe.saveAndRemove(openPaaSUser, "meeting-room", "laptop");

        // when
        String response = given()
            .body("""
                {
                  "q" : "meeting",
                  "objectTypes" : [ "resource" ],
                  "limit" : 10
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        // then
        assertThatJson(response)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("[]");
    }

    @Test
    void shouldNotReturnResourceWhenResourceTypeExcluded(TwakeCalendarGuiceServer server) {
        // given
        OpenPaaSUser openPaaSUser = server.getProbe(CalendarDataProbe.class).getUser(USERNAME);
        ResourceProbe resourceProbe = server.getProbe(ResourceProbe.class);
        resourceProbe.save(openPaaSUser, "meeting-room", "laptop");

        // when
        String response = given()
            .body("""
                {
                  "q" : "meeting",
                  "objectTypes" : [ "user", "contact" ],
                  "limit" : 10
                }""")
            .when()
            .post()
            .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        // then
        assertThatJson(response)
            .isArray()
            .isEmpty();
    }

    @Test
    void shouldRespectLimitWhenSearchingResources(TwakeCalendarGuiceServer server) {
        // given
        OpenPaaSUser openPaaSUser = server.getProbe(CalendarDataProbe.class).getUser(USERNAME);
        ResourceProbe resourceProbe = server.getProbe(ResourceProbe.class);

        resourceProbe.save(openPaaSUser, "meeting-room-1", "laptop");
        resourceProbe.save(openPaaSUser, "meeting-room-2", "laptop");
        resourceProbe.save(openPaaSUser, "meeting-room-3", "laptop");

        // when
        String response = given()
            .body("""
                {
                  "q" : "meeting-room",
                  "objectTypes" : [ "resource" ],
                  "limit" : 2
                }""")
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        // then
        assertThatJson(response)
            .isArray()
            .hasSize(2);
    }

    @Test
    void shouldOnlyReturnUsersFromAuthenticatedUserDomain(TwakeCalendarGuiceServer server) {
        // GIVEN: two distinct domains with users
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        Domain ownedDomain = Domain.of("owned-domain.tld");
        Domain foreignDomain = Domain.of("foreign-domain.tld");

        calendarDataProbe.addDomain(ownedDomain)
            .addDomain(foreignDomain);

        Username ownedDomainUser1 = Username.fromLocalPartWithDomain("alice1", ownedDomain.asString());
        Username ownedDomainUser2 = Username.fromLocalPartWithDomain("alice2", ownedDomain.asString());
        Username foreignDomainUser = Username.fromLocalPartWithDomain("bob", foreignDomain.asString());

        calendarDataProbe.addUser(ownedDomainUser1, PASSWORD);
        calendarDataProbe.addUser(ownedDomainUser2, PASSWORD);
        calendarDataProbe.addUser(foreignDomainUser, PASSWORD);

        // WHEN: searching users as a caller-domain user
        String response = given()
            .auth().preemptive()
            .basic(ownedDomainUser1.asString(), PASSWORD)
            .body("""
                {
                  "q": "",
                  "objectTypes": ["user"],
                  "limit": 10
                }
                """)
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        // THEN: only users belonging to the caller domain are returned
        assertThatJson(response)
            .isArray()
            .anySatisfy(json ->
                assertThatJson(json)
                    .node("emailAddresses[0].value")
                    .isEqualTo(ownedDomainUser1.asString()))
            .anySatisfy(json ->
                assertThatJson(json)
                    .node("emailAddresses[0].value")
                    .isEqualTo(ownedDomainUser2.asString()));

        assertThatJson(response)
            .isArray()
            .noneSatisfy(json ->
                assertThatJson(json)
                    .node("emailAddresses[0].value")
                    .isEqualTo(foreignDomainUser.asString()));
    }

    @Test
    void shouldExcludeUserObjectTypeWhenUserSearchIsDisabledForDomain(TwakeCalendarGuiceServer server) {
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(DISABLED_DOMAIN_1);

        Username disabledDomainUser = Username.fromLocalPartWithDomain("bob", DISABLED_DOMAIN_1.asString());
        Username targetUser = Username.fromLocalPartWithDomain("alice", DOMAIN);

        calendarDataProbe.addUser(disabledDomainUser, PASSWORD);
        calendarDataProbe.addUser(targetUser, PASSWORD);

        MemoryAutoCompleteModule.Probe autoCompleteProbe = server.getProbe(MemoryAutoCompleteModule.Probe.class);
        autoCompleteProbe.add(disabledDomainUser.asString(), targetUser.asString(), "alice", "twake");

        given()
            .auth().preemptive()
            .basic(disabledDomainUser.asString(), PASSWORD)
            .body("""
                {
                  "q": "alice",
                  "objectTypes": ["user", "contact"],
                  "limit": 10
                }
                """)
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("objectType", not(hasItem("user")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[\"user\"]",
        "[\"contact\"]",
        "[\"user\", \"contact\"]"
    })
    void shouldExcludeUserObjectTypeWhenUserSearchIsDisabledForDomainAndTargetIsOnlyUser(String objectTypes, TwakeCalendarGuiceServer server) {
        // given
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(DISABLED_DOMAIN_1);

        Username disabledDomainUser = Username.fromLocalPartWithDomain("bob", DISABLED_DOMAIN_1.asString());
        Username targetUser = Username.fromLocalPartWithDomain("alice", DISABLED_DOMAIN_1.asString());

        calendarDataProbe.addUser(disabledDomainUser, PASSWORD);
        calendarDataProbe.addUser(targetUser, PASSWORD);

        // when / then
        given()
            .auth().preemptive()
            .basic(disabledDomainUser.asString(), PASSWORD)
            .body("""
                {
                  "q": "alice",
                  "objectTypes": %s,
                  "limit": 10
                }
                """.formatted(objectTypes))
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("", hasSize(0));
    }

    @Test
    void shouldAllowDisabledDomainUserToSearchTheirOwnContacts(TwakeCalendarGuiceServer server) {
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(DISABLED_DOMAIN_1);

        Username disabledDomainUser = Username.fromLocalPartWithDomain("bob", DISABLED_DOMAIN_1.asString());
        calendarDataProbe.addUser(disabledDomainUser, PASSWORD);

        MemoryAutoCompleteModule.Probe autoCompleteProbe = server.getProbe(MemoryAutoCompleteModule.Probe.class);
        autoCompleteProbe.add(disabledDomainUser.asString(), "contact1@domain.tld", "alice", "contact");
        autoCompleteProbe.add(disabledDomainUser.asString(), "contact2@domain.tld", "alice", "other");

        String response = given()
            .auth().preemptive()
            .basic(disabledDomainUser.asString(), PASSWORD)
            .body("""
                {
                  "q": "alice",
                  "objectTypes": ["contact"],
                  "limit": 10
                }
                """)
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isArray()
            .anySatisfy(json ->
                assertThatJson(json)
                    .node("objectType")
                    .isEqualTo("contact"));

        assertThatJson(response)
            .isArray()
            .anySatisfy(json ->
                assertThatJson(json)
                    .node("emailAddresses[0].value")
                    .isEqualTo("contact1@domain.tld"))
            .anySatisfy(json ->
                assertThatJson(json)
                    .node("emailAddresses[0].value")
                    .isEqualTo("contact2@domain.tld"));
    }

    @Test
    void shouldReturnEmptyWhenUserSearchDisabledAndOnlyUserRequested(TwakeCalendarGuiceServer server) {
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(DISABLED_DOMAIN_2);

        Username disabledDomainUser = Username.fromLocalPartWithDomain("bob", DISABLED_DOMAIN_2.asString());
        Username targetUser = Username.fromLocalPartWithDomain("alice", DOMAIN);

        calendarDataProbe.addUser(disabledDomainUser, PASSWORD);
        calendarDataProbe.addUser(targetUser, PASSWORD);

        MemoryAutoCompleteModule.Probe autoCompleteProbe = server.getProbe(MemoryAutoCompleteModule.Probe.class);
        autoCompleteProbe.add(disabledDomainUser.asString(), targetUser.asString(), "alice", "twake");

        given()
            .auth().preemptive()
            .basic(disabledDomainUser.asString(), PASSWORD)
            .body("""
                {
                  "q": "alice",
                  "objectTypes": ["user"],
                  "limit": 10
                }
                """)
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("", hasSize(0));
    }

    @Test
    void shouldOnlyReturnContactsFromAuthenticatedUserDomain(TwakeCalendarGuiceServer server) {
        // GIVEN: contacts belonging to two different domains
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);

        Domain ownedDomain = Domain.of("owned-domain.tld");
        Domain foreignDomain = Domain.of("foreign-domain.tld");
        Username ownedDomainUser = Username.fromLocalPartWithDomain("alice", ownedDomain.asString());
        Username foreignDomainUser = Username.fromLocalPartWithDomain("bob", foreignDomain.asString());

        calendarDataProbe.addDomain(ownedDomain)
            .addUser(ownedDomainUser, PASSWORD);
        calendarDataProbe.addDomain(foreignDomain)
            .addUser(foreignDomainUser, PASSWORD);

        MemoryAutoCompleteModule.Probe contactProbe = server.getProbe(MemoryAutoCompleteModule.Probe.class);
        contactProbe.add(ownedDomainUser.asString(), "contact1@" + ownedDomain.asString(), "contact", "owned");
        contactProbe.add(foreignDomainUser.asString(), "contact2@" + foreignDomain.asString(), "contact", "foreign");

        // WHEN: searching contacts as owned-domain user
        String response = given()
            .auth().preemptive()
            .basic(ownedDomainUser.asString(), PASSWORD)
            .body("""
                {
                  "q": "",
                  "objectTypes": ["contact"],
                  "limit": 10
                }
                """)
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        // THEN: only contacts belonging to the authenticated user domain are returned
        assertThatJson(response)
            .isArray()
            .anySatisfy(json ->
                assertThatJson(json)
                    .node("emailAddresses[0].value")
                    .isEqualTo("contact1@" + ownedDomain.asString()));

        assertThatJson(response)
            .isArray()
            .noneSatisfy(json ->
                assertThatJson(json)
                    .node("emailAddresses[0].value")
                    .isEqualTo("contact2@" + foreignDomain.asString()));
    }

    @Test
    void shouldOnlyReturnResourcesFromUserDomain(TwakeCalendarGuiceServer server) {
        // GIVEN: resources in two different domains
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);

        Domain ownedDomain = Domain.of("owned-domain.tld");
        Domain foreignDomain = Domain.of("foreign-domain.tld");
        Username ownedDomainUser = Username.fromLocalPartWithDomain("alice", ownedDomain.asString());
        Username foreignDomainUser = Username.fromLocalPartWithDomain("bob", foreignDomain.asString());
        calendarDataProbe.addDomain(ownedDomain)
            .addUser(ownedDomainUser, PASSWORD);

        calendarDataProbe.addDomain(foreignDomain)
            .addUser(foreignDomainUser, PASSWORD);

        ResourceProbe resourceProbe = server.getProbe(ResourceProbe.class);

        OpenPaaSUser ownedOpenPaaSUser = calendarDataProbe.getUser(ownedDomainUser);
        OpenPaaSUser foreignOpenPaaSUser = calendarDataProbe.getUser(foreignDomainUser);

        resourceProbe.save(ownedOpenPaaSUser, "owned-room", "laptop");
        resourceProbe.save(foreignOpenPaaSUser, "foreign-room", "laptop");

        // WHEN: searching resources as owned-domain user
        String response = given()
            .auth().preemptive()
            .basic(ownedDomainUser.asString(), PASSWORD)
            .body("""
                {
                  "q": "room",
                  "objectTypes": ["resource"],
                  "limit": 10
                }
                """)
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        // THEN: only resources belonging to the user domain are returned
        assertThatJson(response)
            .isArray()
            .anySatisfy(json ->
                assertThatJson(json)
                    .node("names[0].displayName")
                    .isEqualTo("owned-room"));

        assertThatJson(response)
            .isArray()
            .noneSatisfy(json ->
                assertThatJson(json)
                    .node("names[0].displayName")
                    .isEqualTo("foreign-room"));
    }

}
