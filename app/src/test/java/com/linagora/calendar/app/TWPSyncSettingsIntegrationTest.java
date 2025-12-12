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

package com.linagora.calendar.app;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static io.restassured.http.ContentType.JSON;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.james.backends.rabbitmq.RabbitMQExtension.IsolationPolicy.WEAK;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.utils.GuiceProbe;
import org.apache.james.utils.WebAdminGuiceProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.Fixture;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.tmail.saas.rabbitmq.settings.TWPSettingsConsumer;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.parsing.Parser;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;

class TWPSyncSettingsIntegrationTest {
    private static final Domain DOMAIN = Domain.of("domain.tld");
    private static final String PASSWORD = "secret";
    private static final Username USERNAME = Username.of("bob@domain.tld");
    private static final String LANGUAGE_KEY = "language";
    private static final String LANGUAGE_FR = "fr";
    private static final String LANGUAGE_EN = "en";
    private static final long FIRST_VERSION = 1;
    private static final String EXCHANGE_NAME = "settings";
    private static final String ROUTING_KEY = "user.settings.updated";
    private static final String QUERY_LANGUAGE = """
        [
          {
            "name": "core",
            "keys": [ "language" ]
          }
        ]""";

    public static class TWPSettingsProbe implements GuiceProbe {
        @Inject
        private TWPSettingsConsumer consumer;

        public void closeConsumer() {
            consumer.close();
        }
    }

    @RegisterExtension
    @Order(1)
    private static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(WEAK);

    @RegisterExtension
    @Order(2)
    TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        DavModuleTestHelper.RABBITMQ_MODULE.apply(rabbitMQExtension),
        DavModuleTestHelper.BY_PASS_MODULE,
        AppTestHelper.OIDC_BY_PASS_MODULE,
        binder -> {
            Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding().to(DomainAdminProbe.class);
            Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding().to(TWPSettingsProbe.class);
        });

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        server.getProbe(CalendarDataProbe.class)
            .addDomain(DOMAIN)
            .addUser(USERNAME, PASSWORD);

        PreemptiveBasicAuthScheme basicAuthScheme = new PreemptiveBasicAuthScheme();
        basicAuthScheme.setUserName(USERNAME.asString());
        basicAuthScheme.setPassword(PASSWORD);
        RestAssured.defaultParser = Parser.JSON;
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(JSON)
            .setAccept(JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setBasePath("")
            .setAuth(basicAuthScheme)
            .build();

        RequestSpecification webadminRequestSpecification = new RequestSpecBuilder()
            .setContentType(JSON)
            .setAccept(JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(WebAdminGuiceProbe.class).getWebAdminPort().getValue())
            .setBasePath("/")
            .build();

        Fixture.awaitAtMost.untilAsserted(() -> given(webadminRequestSpecification)
            .get("/healthcheck")
        .then()
            .statusCode(200)
            .body("checks.find { it.componentName == 'TWPSettingsQueueConsumerHealthCheck' }.status",
                equalTo("healthy")));
    }

    @AfterEach
    void tearDown(TwakeCalendarGuiceServer server) {
        server.getProbe(TWPSettingsProbe.class).closeConsumer();
    }

    @Test
    void shouldUpdateLanguageSettingViaTWPAmqpMessage() {
        String message = createSettingsUpdateMessage(USERNAME, Map.of(LANGUAGE_KEY, LANGUAGE_FR), FIRST_VERSION);
        publishAmqpSettingsMessage(message);

        Fixture.awaitAtMost.untilAsserted(() -> {
            given()
                .body(QUERY_LANGUAGE)
            .when()
                .post("/api/configurations")
            .then()
                .statusCode(200)
                .body("[0].configurations[0].value", equalTo(LANGUAGE_FR));
        });
    }

    @Test
    void shouldUpdateLanguageWithoutAffectingOtherSettings() {
        // Given: initial timezone
        String initialResponseJson =
            given()
                .body("""
                    [
                      {
                        "name": "core",
                        "keys": [ "datetime" ]
                      }
                    ]""")
            .when()
                .post("/api/configurations")
            .then()
                .statusCode(200)
                .extract()
                .asString();

        String initialTimezone = JsonPath.from(initialResponseJson)
            .getString("[0].configurations.find { it.name == 'datetime' }.value.timeZone");
        assertThat(initialTimezone).isNotBlank();

        // When: publish AMQP message to update language field
        String message = createSettingsUpdateMessage(USERNAME, Map.of(LANGUAGE_KEY, LANGUAGE_FR), FIRST_VERSION);
        publishAmqpSettingsMessage(message);

        // Then: verify language is updated AND timezone is preserved
        Fixture.awaitAtMost.untilAsserted(() -> {
            String updatedResponseJson =
                given()
                    .body("""
                        [
                          {
                            "name": "core",
                            "keys": [ "language", "datetime" ]
                          }
                        ]""")
                .when()
                    .post("/api/configurations")
                .then()
                    .statusCode(200)
                    .extract()
                    .asString();

            String updatedLanguage = JsonPath.from(updatedResponseJson)
                .getString("[0].configurations.find { it.name == 'language' }.value");

            String updatedTimezone = JsonPath.from(updatedResponseJson)
                .getString("[0].configurations.find { it.name == 'datetime' }.value.timeZone");

            assertThat(updatedLanguage).isEqualTo(LANGUAGE_FR);
            assertThat(updatedTimezone).isEqualTo(initialTimezone);
        });
    }

    @Test
    void shouldApplyHighestVersionWhenMessagesArriveOutOfOrder() throws InterruptedException {
        // When: publish out-of-order AMQP messages
        publishAmqpSettingsMessage(createSettingsUpdateMessage(USERNAME, Map.of(LANGUAGE_KEY, LANGUAGE_EN), FIRST_VERSION + 1));
        publishAmqpSettingsMessage(createSettingsUpdateMessage(USERNAME, Map.of(LANGUAGE_KEY, LANGUAGE_FR), FIRST_VERSION + 2));
        publishAmqpSettingsMessage(createSettingsUpdateMessage(USERNAME, Map.of(LANGUAGE_KEY, "vi"), FIRST_VERSION));

        // Then: wait until consumer finishes and highest version is applied
        Thread.sleep(2000);
        String responseJson =
            given()
                .body(QUERY_LANGUAGE)
            .when()
                .post("/api/configurations")
            .then()
                .statusCode(200)
                .extract()
                .asString();

        String finalLanguage = JsonPath.from(responseJson)
            .getString("[0].configurations.find { it.name == 'language' }.value");
        assertThat(finalLanguage).isEqualTo(LANGUAGE_FR);
    }

    @Test
    void shouldContinueProcessingWhenConsumeMessageWithUserNotFound() throws Exception {
        Username unknownUser = Username.of(UUID.randomUUID() + "@domain.tld");

        // publish AMQP message for non-existing user
        publishAmqpSettingsMessage(createSettingsUpdateMessage(unknownUser, Map.of(LANGUAGE_KEY, LANGUAGE_FR), FIRST_VERSION));
        // Wait a bit for consumer to process & error handling to complete
        Thread.sleep(500);

        // publish a valid message for an existing user
        publishAmqpSettingsMessage(createSettingsUpdateMessage(USERNAME, Map.of(LANGUAGE_KEY, LANGUAGE_FR), FIRST_VERSION + 1));

        // Then
        Fixture.awaitAtMost.untilAsserted(() -> {
            String responseJson =
                given()
                    .body(QUERY_LANGUAGE)
                .when()
                    .post("/api/configurations")
                .then()
                    .statusCode(200)
                    .extract()
                    .asString();

            String updatedLanguage = JsonPath.from(responseJson)
                .getString("[0].configurations.find { it.name == 'language' }.value");

            assertThat(updatedLanguage).isEqualTo(LANGUAGE_FR);
        });
    }

    private void publishAmqpSettingsMessage(String message) {
        rabbitMQExtension.getSender()
            .send(Mono.just(new OutboundMessage(EXCHANGE_NAME, ROUTING_KEY, message.getBytes(UTF_8))))
            .block();
    }

    private String createSettingsUpdateMessage(Username username,
                                               Map<String, String> settingsUpdatePayload,
                                               long version) {
        ImmutableMap<String, String> payload = ImmutableMap.<String, String>builder()
            .putAll(settingsUpdatePayload)
            .put("email", username.asString())
            .build();

        String payloadJson = payload.entrySet().stream()
            .map(entry -> "\"" + entry.getKey() + "\": \"" + entry.getValue() + "\"")
            .collect(Collectors.joining(",\n"));

        String template = """
            {
                "source": "twake-calendar",
                "nickname": "{NICK}",
                "request_id": "{REQ_ID}",
                "timestamp": {TIMESTAMP},
                "payload": {
                    {PAYLOAD}
                },
                "version": {VERSION}
            }
            """;

        return template
            .replace("{NICK}", username.asString())
            .replace("{REQ_ID}", UUID.randomUUID().toString())
            .replace("{TIMESTAMP}", String.valueOf(System.currentTimeMillis()))
            .replace("{PAYLOAD}", payloadJson)
            .replace("{VERSION}", String.valueOf(version));
    }
}
