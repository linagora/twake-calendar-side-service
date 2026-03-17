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

import static io.restassured.RestAssured.when;
import static io.restassured.RestAssured.with;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.apache.http.HttpStatus;
import org.apache.james.utils.GuiceProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.BookingLinkProbe;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class BookingLinkDeleteRouteTest {

    private static final boolean ACTIVE = true;
    private static final String PASSWORD = "secret";

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    @RegisterExtension
    @Order(2)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        binder -> {
            Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding()
                .to(BookingLinkProbe.class);
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private BookingLinkProbe bookingLinkProbe;
    private OpenPaaSUser openPaaSUser;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        openPaaSUser = sabreDavExtension.newTestUser();
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(openPaaSUser.username().getDomainPart().get());
        calendarDataProbe.addUserToRepository(openPaaSUser.username(), PASSWORD);

        bookingLinkProbe = server.getProbe(BookingLinkProbe.class);

        PreemptiveBasicAuthScheme basicAuthScheme = new PreemptiveBasicAuthScheme();
        basicAuthScheme.setUserName(openPaaSUser.username().asString());
        basicAuthScheme.setPassword(PASSWORD);

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setBasePath("")
            .setAuth(basicAuthScheme)
            .build();
    }

    @Test
    void shouldReturn204WhenDeletingExistingBookingLink() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        when()
            .delete("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);
    }

    @Test
    void shouldDeleteBookingLink() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        when()
            .delete("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        assertThat(bookingLinkProbe.listBookingLinks(openPaaSUser.username())).isEmpty();
    }

    @Test
    void shouldOnlyDeleteTheTargetedBookingLink() {
        BookingLink toDelete = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));
        BookingLink toKeep = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(60), ACTIVE, Optional.empty()));

        when()
            .delete("/booking-links/" + toDelete.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        assertThat(bookingLinkProbe.listBookingLinks(openPaaSUser.username()))
            .extracting(BookingLink::publicId)
            .containsExactly(toKeep.publicId());
    }

    @Test
    void shouldReturn404WhenBookingLinkDoesNotExist() {
        when()
            .delete("/booking-links/" + UUID.randomUUID())
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    void shouldNotDeleteBookingLinkOfAnotherUser() {
        OpenPaaSUser otherUser = sabreDavExtension.newTestUser();
        BookingLink otherInserted = bookingLinkProbe.insertBookingLink(otherUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(otherUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        when()
            .delete("/booking-links/" + otherInserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);

        assertThat(bookingLinkProbe.listBookingLinks(otherUser.username())).hasSize(1);
    }

    @Test
    void shouldReturn401WhenUnauthenticated() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        with()
            .auth().none()
            .contentType(ContentType.JSON)
        .when()
            .delete("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_UNAUTHORIZED);
    }

    @Test
    void shouldReturn400WhenPublicIdIsNotAValidUUID() {
        when()
            .delete("/booking-links/not-a-uuid")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }
}
