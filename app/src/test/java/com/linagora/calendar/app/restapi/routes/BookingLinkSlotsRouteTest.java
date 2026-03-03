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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.http.HttpStatus;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.core.Username;
import org.apache.james.utils.GuiceProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.api.booking.AvailabilityRule.FixedAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class BookingLinkSlotsRouteTest {
    private static final String PASSWORD = "secret";

    static class BookingLinkSlotsProbe implements GuiceProbe {
        private final BookingLinkDAO bookingLinkDAO;

        @Inject
        BookingLinkSlotsProbe(BookingLinkDAO bookingLinkDAO) {
            this.bookingLinkDAO = bookingLinkDAO;
        }

        BookingLink insert(Username username, BookingLinkInsertRequest request) {
            return bookingLinkDAO.insert(username, request).block();
        }
    }

    private OpenPaaSUser openPaaSUser;

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
        binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
            .addBinding()
            .to(BookingLinkSlotsProbe.class));

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        Username username = Username.of("owner-%s@linagora.com".formatted(UUID.randomUUID()));
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addUser(username, PASSWORD);
        openPaaSUser = calendarDataProbe.getUser(username);

        PreemptiveBasicAuthScheme basicAuthScheme = new PreemptiveBasicAuthScheme();
        basicAuthScheme.setUserName(openPaaSUser.username().asString());
        basicAuthScheme.setPassword(PASSWORD);

        int restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(restApiPort)
            .setBasePath("")
            .setAuth(basicAuthScheme)
            .build();
    }

    @Test
    void getSlotsShouldReturn200ResponseInExpectedJsonFormat(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(
            new CalendarURL(openPaaSUser.id(), openPaaSUser.id()),
            Duration.ofMinutes(30),
            BookingLinkInsertRequest.ACTIVE,
            Optional.of(AvailabilityRules.of(new FixedAvailabilityRule(
                ZonedDateTime.parse("2026-01-26T09:00:00Z"),
                ZonedDateTime.parse("2026-01-26T12:00:00Z")))));

        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class)
            .insert(openPaaSUser.username(), insertRequest);

        String response = given().log().all()
            .when()
            .get("/calendar/api/booking-links/%s/slots?from=2026-01-26T00:00:00Z&to=2026-01-27T00:00:00Z".formatted(inserted.publicId().value())).prettyPeek()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                  "durationMinutes": 30,
                  "range": {
                    "from": "2026-01-26T00:00:00Z",
                    "to": "2026-01-27T00:00:00Z"
                  },
                  "slots": [
                    "2026-01-26T09:00:00Z",
                    "2026-01-26T09:30:00Z",
                    "2026-01-26T10:00:00Z",
                    "2026-01-26T10:30:00Z",
                    "2026-01-26T11:00:00Z",
                    "2026-01-26T11:30:00Z"
                  ]
                }
                """);
    }
}
