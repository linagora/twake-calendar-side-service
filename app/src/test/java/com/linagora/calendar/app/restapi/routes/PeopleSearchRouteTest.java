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

        public ResourceId save(OpenPaaSUser requestUser, String name, String icon) {
            ResourceAdministrator administrator = new ResourceAdministrator(requestUser.id(), "user");

            OpenPaaSDomain openPaaSDomain = domainDAO.retrieve(requestUser.username().getDomainPart().get()).block();

            ResourceInsertRequest insertRequest = new ResourceInsertRequest(List.of(administrator),
                requestUser.id(),
                name + " description",
                openPaaSDomain.id(),
                icon,
                name);
            return resourceDAO.insert(insertRequest).block();
        }

        public ResourceId saveAndRemove(OpenPaaSUser requestUser, String name, String icon) {
            ResourceAdministrator administrator = new ResourceAdministrator(requestUser.id(), "user");

            OpenPaaSDomain openPaaSDomain = domainDAO.retrieve(requestUser.username().getDomainPart().get()).block();

            ResourceInsertRequest insertRequest = new ResourceInsertRequest(List.of(administrator),
                requestUser.id(),
                name + " description",
                openPaaSDomain.id(),
                icon,
                name);

            return resourceDAO.insert(insertRequest)
                .flatMap(resourceId -> resourceDAO.softDelete(resourceId).thenReturn(resourceId)).block();
        }

        public List<Resource> listAll() {
            return resourceDAO.findAll().collectList().block();
        }
    }

    private static final String DOMAIN = "open-paas.ltd";
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
                    "photos": [ { "url": "https://e-calendrier.avocat.fr/linagora.esn.resource/images/icon/laptop.svg", "type": "default" } ]
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

}
