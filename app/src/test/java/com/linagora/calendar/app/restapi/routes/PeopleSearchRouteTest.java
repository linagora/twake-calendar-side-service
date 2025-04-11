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

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.http.HttpStatus;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.name.Names;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.app.modules.MemoryAutoCompleteModule;
import com.linagora.calendar.restapi.RestApiServerProbe;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import net.javacrumbs.jsonunit.core.Option;

public class PeopleSearchRouteTest {

    private static final String DOMAIN = "open-paas.ltd";
    private static final String PASSWORD = "secret";
    private static final Username USERNAME = Username.fromLocalPartWithDomain("bob", DOMAIN);

    @RegisterExtension
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        binder -> binder.bind(URL.class).annotatedWith(Names.named("userInfo"))
            .toProvider(() -> {
                try {
                    return new URL("https://neven.to.be.called.com");
                } catch (MalformedURLException e) {
                    throw new RuntimeException(e);
                }
            }));

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
            .inPath("[0].objectType")
            .isEqualTo("user");
    }

    @Test
    void shouldReturnContactObjectTypeWhenUserTypeIsExcludedInFilter(TwakeCalendarGuiceServer server) {
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
}
