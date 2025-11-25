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
import static org.apache.james.backends.rabbitmq.RabbitMQExtension.IsolationPolicy.WEAK;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.MailboxSessionUtil;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;

class UserConfigurationRouteTest {

    private static final String DOMAIN = "open-paas.ltd";
    private static final String PASSWORD = "secret";
    private static final Username USERNAME = Username.fromLocalPartWithDomain("bob", DOMAIN);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @RegisterExtension
    @Order(1)
    private static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(WEAK);

    @RegisterExtension
    @Order(2)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        AppTestHelper.EVENT_BUS_BY_PASS_MODULE,
        AppTestHelper.BY_PASS_MODULE.apply(rabbitMQExtension));

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
            .setContentType(JSON)
            .setAccept(JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setBasePath("")
            .setAuth(basicAuthScheme)
            .build();

        server.getProbe(CalendarDataProbe.class)
            .addUser(USERNAME, PASSWORD);
    }

    @Test
    void putShouldReturn204WhenValidRequest() {
        given()
            .body("""
                [
                  {
                    "name": "core",
                    "configurations": [
                      {
                        "name": "homePage",
                        "value": "unifiedinbox"
                      },
                      {
                        "name": "businessHours",
                        "value": [
                          {
                            "start": "09:00",
                            "end": "17:05",
                            "daysOfWeek": [1, 2, 3, 4, 5]
                          }
                        ]
                      },
                      {
                        "name": "datetime",
                        "value": {
                          "timeZone": "Asia/Ho_Chi_Minh",
                          "use24hourFormat": true
                        }
                      },
                      {
                        "name": "language",
                        "value": "vi"
                      }
                    ]
                  },
                  {
                    "name": "linagora.esn.unifiedinbox",
                    "configurations": [
                      {
                        "name": "useEmailLinks",
                        "value": true
                      }
                    ]
                  }
                ]
                """)
        .when()
            .put("/api/configurations?scope=user")
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);
    }

    @Test
    void putShouldSaveUserConfigurationWhenSuccessful() {
        String configuration = """
            [
              {
                "name": "core",
                "configurations": [
                  {
                    "name": "language",
                    "value": "vi"
                  }
                ]
              }
            ]
            """;

        given()
            .body(configuration)
        .when()
            .put("/api/configurations?scope=user")
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .body("""
                [ {
                  "name" : "core",
                  "keys" : [ "language" ]
                } ]""")
        .when()
            .post("/api/configurations")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("""
            [
                {
                    "name": "core",
                    "configurations": [
                        {
                            "name": "language",
                            "value": "vi"
                        }
                    ]
                }
            ]""");
    }

    @Test
    void putShouldSaveUserConfigurationWhenComplexRequest(TwakeCalendarGuiceServer server) {
        given()
            .body("""
                [
                  {
                    "name": "core",
                    "configurations": [
                      {
                        "name": "homePage",
                        "value": "unifiedinbox"
                      },
                      {
                        "name": "businessHours",
                        "value": [
                          {
                            "start": "09:00",
                            "end": "17:05",
                            "daysOfWeek": [1, 2, 3, 4, 5]
                          }
                        ]
                      },
                      {
                        "name": "datetime",
                        "value": {
                          "timeZone": "Asia/Ho_Chi_Minh",
                          "use24hourFormat": true
                        }
                      },
                      {
                        "name": "language",
                        "value": "vi"
                      }
                    ]
                  },
                  {
                    "name": "linagora.esn.unifiedinbox",
                    "configurations": [
                      {
                        "name": "useEmailLinks",
                        "value": true
                      }
                    ]
                  }
                ]
                """)
        .when()
            .put("/api/configurations?scope=user")
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        List<ConfigurationEntry> configurationEntries = server.getProbe(CalendarDataProbe.class).
            retrieveConfiguration(MailboxSessionUtil.create(USERNAME));

        assertThat(configurationEntries)
            .usingElementComparator(Comparator.comparing(Object::toString))
            .contains(
                ConfigurationEntry.of("linagora.esn.unifiedinbox", "useEmailLinks", BooleanNode.TRUE),
                ConfigurationEntry.of("core", "homePage", new TextNode("unifiedinbox")),
                ConfigurationEntry.of("core", "language", new TextNode("vi")),
                ConfigurationEntry.of("core", "datetime", toJsonNode("""
                    {
                         "timeZone": "Asia/Ho_Chi_Minh",
                         "use24hourFormat": true
                     }""")),
                ConfigurationEntry.of("core", "businessHours", toJsonNode("""
                    [{
                        "start": "09:00",
                        "end": "17:05",
                        "daysOfWeek": [1, 2, 3, 4, 5]
                      }]""")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/configurations",  // Missing scope
        "/api/configurations?scope=invalid", // Invalid scope
        "/api/configurations?scope=", // Invalid scope
    })
    void putShouldReturn400WhenInvalidScopeParameter(String invalidUrl) {
        given()
            .body("""
                [
                  {
                    "name": "core",
                    "configurations": [
                      {
                        "name": "homePage",
                        "value": "unifiedinbox"
                      }
                    ]
                  }
                ]
                """)
        .when()
            .put(invalidUrl)
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[{\"name\": \"core\" }]",  // Missing configurations
        "[{\"configurations\": [{\"name\": \"language\", \"value\": \"en\"}]}]", // Missing name
        "[{\"name\": \"core\", \"configurations\": [{\"name\": \"language\"}]}]",  // Missing value for 'language'
        "[{\"name\": \"core\", \"configurations\": [{\"value\": \"language\"}]}]",  // Missing key for 'language'
        "invalid_json",
        "{}",
        ""
    })
    void putShouldReturn400WhenInvalidRequest(String bodyRequest) {
        String response = given()
            .body(bodyRequest)
        .when()
            .put("/api/configurations?scope=user")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response).isEqualTo("""
            {
                 "error": {
                     "code": 400,
                     "message": "Bad request",
                     "details": "Invalid request body"
                 }
             }
            """);
    }

    @Test
    void putShouldSaveDisplayWeekNumbersConfiguration() {
        String configuration = """
            [
              {
                "name": "calendar",
                "configurations": [
                  {
                    "name": "displayWeekNumbers",
                    "value": false
                  }
                ]
              }
            ]
            """;

        given()
            .body(configuration)
        .when()
            .put("/api/configurations?scope=user")
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .body("""
                [ {
                  "name" : "calendar",
                  "keys" : [ "displayWeekNumbers" ]
                } ]""")
        .when()
            .post("/api/configurations")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("""
            [
                {
                    "name": "calendar",
                    "configurations": [
                        {
                            "name": "displayWeekNumbers",
                            "value": false
                        }
                    ]
                }
            ]""");
    }

    @Test
    void postShouldReturnDefaultTrueForDisplayWeekNumbersWhenNotConfigured() {
        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .body("""
                [ {
                  "name" : "calendar",
                  "keys" : [ "displayWeekNumbers" ]
                } ]""")
        .when()
            .post("/api/configurations")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("""
            [
                {
                    "name": "calendar",
                    "configurations": [
                        {
                            "name": "displayWeekNumbers",
                            "value": true
                        }
                    ]
                }
            ]""");
    }

    JsonNode toJsonNode(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize JSON", e);
        }
    }

}
