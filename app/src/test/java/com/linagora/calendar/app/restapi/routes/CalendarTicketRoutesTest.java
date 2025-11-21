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

import static com.linagora.calendar.app.AppTestHelper.BY_PASS_MODULE;
import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static io.restassured.http.ContentType.JSON;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.james.backends.rabbitmq.RabbitMQExtension.IsolationPolicy.WEAK;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.http.HttpStatus;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.utils.GuiceProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.tmail.james.jmap.ticket.TicketManager;
import com.linagora.tmail.james.jmap.ticket.TicketValue;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import reactor.core.publisher.Mono;

public class CalendarTicketRoutesTest {

    static class TicketManagerProbe implements GuiceProbe {
        private final TicketManager ticketManager;

        @Inject
        TicketManagerProbe(TicketManager ticketManager) {
            this.ticketManager = ticketManager;
        }

        public Username validate(String ticket, String inetAddressValue) throws UnknownHostException {
            TicketValue ticketValue = new TicketValue(UUID.fromString(ticket));
            InetAddress inetAddress = InetAddress.getByName(inetAddressValue);
            return Mono.from(ticketManager.validate(ticketValue, inetAddress)).block();
        }
    }

    private static final String DOMAIN = "open-paas.ltd";
    private static final String PASSWORD = "secret";
    private static final Username USERNAME = Username.fromLocalPartWithDomain("bob", DOMAIN);

    @RegisterExtension
    @Order(1)
    private static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(WEAK);

    @RegisterExtension
    @Order(2)
    static TwakeCalendarExtension extension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        BY_PASS_MODULE.apply(rabbitMQExtension),
        DavModuleTestHelper.BY_PASS_MODULE,
        binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
            .addBinding().to(TicketManagerProbe.class));

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        server.getProbe(CalendarDataProbe.class).addDomain(Domain.of(DOMAIN));
        server.getProbe(CalendarDataProbe.class)
            .addUserToRepository(USERNAME, PASSWORD);

        int restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();
        PreemptiveBasicAuthScheme auth = new PreemptiveBasicAuthScheme();
        auth.setUserName(USERNAME.asString());
        auth.setPassword(PASSWORD);

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setAuth(auth)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(restApiPort)
            .setBasePath("")
            .build();
    }

    @Test
    void shouldReturnTicketWhenAuthenticatedUserRequestsWs(TwakeCalendarGuiceServer server) throws UnknownHostException {
        String response = given()
            .when()
            .post("/ws/ticket")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                     "clientAddress": "${json-unit.ignore}",
                     "value": "${json-unit.ignore}",
                     "generatedOn": "${json-unit.ignore}",
                     "validUntil": "${json-unit.ignore}",
                     "username": "${json-unit.ignore}"
                 }""");

        String ticketValue = JsonPath.from(response).getString("value");
        String clientAddress = JsonPath.from(response).getString("clientAddress");

        Username claimedUser = server.getProbe(TicketManagerProbe.class).validate(ticketValue, clientAddress);
        assertThat(claimedUser).isEqualTo(USERNAME);
    }

    @Test
    void shouldRejectTicketCreationWhenAuthenticationFails() {
        String response = given()
            .auth().preemptive().basic(USERNAME.asString(), "wrong-password")
        .when()
            .post("/ws/ticket")
        .then()
            .statusCode(HttpStatus.SC_UNAUTHORIZED)
            .extract()
            .body().asString();

        assertThatJson(response)
            .isEqualTo("""
                   {
                    "type":"about:blank",
                    "status":401,
                    "detail":"Wrong credentials provided"
                }""");
    }

    @Test
    void shouldRevokeTicketSuccessfully() {
        String responseBody = when()
            .post("/ws/ticket")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        String ticketValue = JsonPath.from(responseBody).getString("value");

        given()
            .when()
        .when()
            .delete("/ws/ticket/" + ticketValue)
            .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);
    }

    @Test
    void shouldAllowUsingTicketToAuthenticateAnotherApi(TwakeCalendarGuiceServer server) throws UnknownHostException {
        // Create a ticket using basic auth
        String ticketResponse = given()
            .when()
            .post("/ws/ticket")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        String ticketValue = JsonPath.from(ticketResponse).getString("value");

        // the generated ticket to authenticate a second ticket creation
        String secondResponse = given()
            .auth().none()
            .queryParam("ticket", ticketValue)
            .when()
            .post("/ws/ticket")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(secondResponse)
            .isEqualTo("""
                {
                     "clientAddress": "${json-unit.ignore}",
                     "value": "${json-unit.ignore}",
                     "generatedOn": "${json-unit.ignore}",
                     "validUntil": "${json-unit.ignore}",
                     "username": "${json-unit.ignore}"
                 }""");
    }

    @Test
    void shouldRejectInvalidTicketWhenAuthenticatingAnotherApi() {
        // Use an invalid / random ticket
        String invalidTicket = UUID.randomUUID().toString();

        String response = given()
            .auth().none()
            .queryParam("ticket", invalidTicket)
        .when()
            .post("/ws/ticket")
        .then()
            .statusCode(HttpStatus.SC_UNAUTHORIZED)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "type": "about:blank",
                    "status": 401,
                    "detail": "${json-unit.ignore}"
                }""");
    }
}
