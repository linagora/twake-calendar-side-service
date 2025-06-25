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
import static io.restassured.RestAssured.request;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.SabreDavProvisioningService;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;
import com.linagora.calendar.storage.event.EventFields;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

public interface CalendarSearchRouteContract {

    String DOMAIN = SabreDavProvisioningService.DOMAIN;
    String PASSWORD = "secret";
    Username USERNAME = Username.fromLocalPartWithDomain("bob", DOMAIN);

    @BeforeEach
    default void setup(TwakeCalendarGuiceServer server) {
        server.getProbe(CalendarDataProbe.class).addDomain(Domain.of(DOMAIN));
        server.getProbe(CalendarDataProbe.class).addUser(USERNAME, PASSWORD);

        PreemptiveBasicAuthScheme auth = new PreemptiveBasicAuthScheme();
        auth.setUserName(USERNAME.asString());
        auth.setPassword(PASSWORD);

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setAuth(auth)
            .setBasePath("")
            .setAccept(ContentType.JSON)
            .setContentType(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .build();
    }

    @Test
    default void shouldSearchCalendarSuccessfully(TwakeCalendarGuiceServer server) throws Exception {
        String userId = "6053022c9da5ef001f430b43";
        String calendarId = "6053022c9da5ef001f430b43";
        String organizerEmail = "organizer@linagora.com";
        String attendeeEmail = "attendee@linagora.com";

        // Match all search criteria
        EventFields event = EventFields.builder()
            .uid("event-1")
            .summary("Title 1")
            .location("office")
            .description("note 1")
            .start(Instant.parse("2025-04-19T11:00:00Z"))
            .end(Instant.parse("2025-04-19T11:30:00Z"))
            .clazz("PUBLIC")
            .allDay(true)
            .isRecurrentMaster(true)
            .organizer(EventFields.Person.of("organizer", organizerEmail))
            .addAttendee(EventFields.Person.of("attendee", attendeeEmail))
            .addResource(new EventFields.Person("resource 1", new MailAddress("resource1@linagora.com")))
            .calendarURL(new CalendarURL(new OpenPaaSId(userId), new OpenPaaSId(calendarId)))
            .dtStamp(Instant.parse("2025-04-18T07:47:48Z"))
            .build();


        // Not match query criteria
        EventFields event2 = EventFields.builder()
            .uid("event-2")
            .summary("Title 1")
            .description("noty 1")
            .start(Instant.parse("2025-04-19T11:00:00Z"))
            .end(Instant.parse("2025-04-19T11:30:00Z"))
            .clazz("PUBLIC")
            .allDay(true)
            .isRecurrentMaster(true)
            .organizer(EventFields.Person.of("organizer", organizerEmail))
            .addAttendee(EventFields.Person.of("attendee", attendeeEmail))
            .calendarURL(new CalendarURL(new OpenPaaSId(userId), new OpenPaaSId(calendarId)))
            .dtStamp(Instant.parse("2025-04-18T07:47:48Z"))
            .build();

        // Not match organizer criteria
        EventFields event3 = EventFields.builder()
            .uid("event-3")
            .summary("Title 1")
            .description("note 1")
            .start(Instant.parse("2025-04-19T11:00:00Z"))
            .end(Instant.parse("2025-04-19T11:30:00Z"))
            .clazz("PUBLIC")
            .allDay(true)
            .isRecurrentMaster(true)
            .organizer(EventFields.Person.of("organizer", "wrong@linagora.com"))
            .addAttendee(EventFields.Person.of("attendee", attendeeEmail))
            .calendarURL(new CalendarURL(new OpenPaaSId(userId), new OpenPaaSId(calendarId)))
            .dtStamp(Instant.parse("2025-04-18T07:47:48Z"))
            .build();

        // Not match attendee criteria
        EventFields event4 = EventFields.builder()
            .uid("event-4")
            .summary("Title 1")
            .description("note 1")
            .start(Instant.parse("2025-04-19T11:00:00Z"))
            .end(Instant.parse("2025-04-19T11:30:00Z"))
            .clazz("PUBLIC")
            .allDay(true)
            .isRecurrentMaster(true)
            .organizer(EventFields.Person.of("organizer", organizerEmail))
            .addAttendee(EventFields.Person.of("attendee", "wrong@linagora.com"))
            .calendarURL(new CalendarURL(new OpenPaaSId(userId), new OpenPaaSId(calendarId)))
            .dtStamp(Instant.parse("2025-04-18T07:47:48Z"))
            .build();

        // Not match calendar url criteria
        EventFields event5 = EventFields.builder()
            .uid("event-5")
            .summary("Title 1")
            .description("note 1")
            .start(Instant.parse("2025-04-19T11:00:00Z"))
            .end(Instant.parse("2025-04-19T11:30:00Z"))
            .clazz("PUBLIC")
            .allDay(true)
            .isRecurrentMaster(true)
            .organizer(EventFields.Person.of("organizer", organizerEmail))
            .addAttendee(EventFields.Person.of("attendee", attendeeEmail))
            .calendarURL(new CalendarURL(new OpenPaaSId(userId), new OpenPaaSId("not-existed")))
            .dtStamp(Instant.parse("2025-04-18T07:47:48Z"))
            .build();

        server.getProbe(CalendarDataProbe.class).indexCalendar(USERNAME, CalendarEvents.of(event));
        server.getProbe(CalendarDataProbe.class).indexCalendar(USERNAME, CalendarEvents.of(event2));
        server.getProbe(CalendarDataProbe.class).indexCalendar(USERNAME, CalendarEvents.of(event3));
        server.getProbe(CalendarDataProbe.class).indexCalendar(USERNAME, CalendarEvents.of(event4));
        server.getProbe(CalendarDataProbe.class).indexCalendar(USERNAME, CalendarEvents.of(event5));

        String requestBody = """
            {
                "calendars": [
                    { "userId": "%s", "calendarId": "%s" }
                ],
                "query": "note",
                "organizers": [ "%s" ],
                "attendees": [ "%s" ]
            }
            """.formatted(userId, calendarId, organizerEmail, attendeeEmail);

        given()
            .body(requestBody)
            .post("/calendar/api/events/search")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("_total_hits", equalTo(1))
            .body("_embedded.events[0]._links.self.href", equalTo("/calendars/" + userId + "/" + calendarId + "/" + "event-1" + ".ics"))
            .body("_embedded.events[0].data.uid", equalTo("event-1"))
            .body("_embedded.events[0].data.summary", equalTo("Title 1"))
            .body("_embedded.events[0].data.location", equalTo("office"))
            .body("_embedded.events[0].data.description", equalTo("note 1"))
            .body("_embedded.events[0].data.organizer.email", equalTo(organizerEmail))
            .body("_embedded.events[0].data.attendees[0].email", equalTo(attendeeEmail))
            .body("_embedded.events[0].data.resources[0].cn", equalTo("resource 1"))
            .body("_embedded.events[0].data.resources[0].email", equalTo("resource1@linagora.com"))
            .body("_embedded.events[0].data.start", equalTo("2025-04-19T11:00:00Z"))
            .body("_embedded.events[0].data.end", equalTo("2025-04-19T11:30:00Z"))
            .body("_embedded.events[0].data.class", equalTo("PUBLIC"))
            .body("_embedded.events[0].data.allDay", equalTo(true))
            .body("_embedded.events[0].data.isRecurrentMaster", equalTo(true))
            .body("_embedded.events[0].data.userId", equalTo(userId))
            .body("_embedded.events[0].data.calendarId", equalTo(calendarId))
            .body("_embedded.events[0].data.dtstamp", equalTo("2025-04-18T07:47:48Z"));
    }

    @Test
    default void shouldReturnResultsBasedOnLimitAndOffset(TwakeCalendarGuiceServer server) throws Exception {
        String userId = "6053022c9da5ef001f430b43";
        String calendarId = "6053022c9da5ef001f430b43";
        String organizerEmail = "organizer@linagora.com";
        String attendeeEmail = "attendee@linagora.com";

        for (int i = 1; i <= 5; i++) {
            EventFields event = EventFields.builder()
                .uid("event-" + i)
                .summary("Event" + i)
                .description("note " + i)
                .start(Instant.parse("2025-04-19T1" + i + ":00:00Z"))
                .end(Instant.parse("2025-04-19T1" + i + ":30:00Z"))
                .clazz("PUBLIC")
                .allDay(false)
                .isRecurrentMaster(false)
                .organizer(EventFields.Person.of("organizer", organizerEmail))
                .addAttendee(EventFields.Person.of("attendee", attendeeEmail))
                .calendarURL(new CalendarURL(new OpenPaaSId(userId), new OpenPaaSId(calendarId)))
                .dtStamp(Instant.parse("2025-04-18T0" + i + ":47:48Z"))
                .build();
            server.getProbe(CalendarDataProbe.class).indexCalendar(USERNAME, CalendarEvents.of(event));
        }

        String requestBody = """
        {
            "calendars": [
                { "userId": "%s", "calendarId": "%s" }
            ],
            "query": "note",
            "organizers": [ "%s" ],
            "attendees": [ "%s" ]
        }
        """.formatted(userId, calendarId, organizerEmail, attendeeEmail);

        // limit=2, offset=0: should return first 2 events
        given()
            .body(requestBody)
            .post("/calendar/api/events/search?limit=2&offset=0")
            .then()
            .statusCode(200)
            .body("_total_hits", equalTo(2))
            .body("_embedded.events[0].data.uid", equalTo("event-1"))
            .body("_embedded.events[1].data.uid", equalTo("event-2"));

        // limit=2, offset=2: should return next 2 events
        given()
            .body(requestBody)
            .post("/calendar/api/events/search?limit=2&offset=2")
            .then()
            .statusCode(200)
            .body("_total_hits", equalTo(2))
            .body("_embedded.events[0].data.uid", equalTo("event-3"))
            .body("_embedded.events[1].data.uid", equalTo("event-4"));

        // limit=2, offset=4: should return last event
        given()
            .body(requestBody)
            .post("/calendar/api/events/search?limit=2&offset=4")
            .then()
            .statusCode(200)
            .body("_total_hits", equalTo(1))
            .body("_embedded.events[0].data.uid", equalTo("event-5"));
    }

    @Test
    default void shouldNotReturnResultsFromUnauthorizedCalendars(TwakeCalendarGuiceServer server) throws Exception {
        Username alice = Username.fromLocalPartWithDomain("alice", DOMAIN);
        String userId = "6053022c9da5ef001f430b43";
        String calendarId = "6053022c9da5ef001f430b43";
        String organizerEmail = "organizer@linagora.com";
        String attendeeEmail = "attendee@linagora.com";

        // Match all search criteria
        EventFields event = EventFields.builder()
            .uid("event-1")
            .summary("Title 1")
            .description("note 1")
            .start(Instant.parse("2025-04-19T11:00:00Z"))
            .end(Instant.parse("2025-04-19T11:30:00Z"))
            .clazz("PUBLIC")
            .allDay(true)
            .isRecurrentMaster(true)
            .organizer(EventFields.Person.of("organizer", organizerEmail))
            .addAttendee(EventFields.Person.of("attendee", attendeeEmail))
            .calendarURL(new CalendarURL(new OpenPaaSId(userId), new OpenPaaSId(calendarId)))
            .dtStamp(Instant.parse("2025-04-18T07:47:48Z"))
            .build();

        server.getProbe(CalendarDataProbe.class).indexCalendar(alice, CalendarEvents.of(event));

        String requestBody = """
            {
                "calendars": [
                    { "userId": "%s", "calendarId": "%s" }
                ],
                "query": "note",
                "organizers": [ "%s" ],
                "attendees": [ "%s" ]
            }
            """.formatted(userId, calendarId, organizerEmail, attendeeEmail);

        given()
            .body(requestBody)
            .post("/calendar/api/events/search")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("_total_hits", equalTo(0));
    }

    @Test
    default void shouldReturn400WhenQueryFieldIsMissing(TwakeCalendarGuiceServer server) {
        String userId = "6053022c9da5ef001f430b43";
        String calendarId = "6053022c9da5ef001f430b43";
        String organizerEmail = "organizer@linagora.com";
        String attendeeEmail = "attendee@linagora.com";

        String requestBody = """
            {
                "calendars": [
                    { "userId": "%s", "calendarId": "%s" }
                ],
                "organizers": [ "%s" ],
                "attendees": [ "%s" ]
            }
            """.formatted(userId, calendarId, organizerEmail, attendeeEmail);

        given()
            .body(requestBody)
            .post("/calendar/api/events/search?limit=30&offset=0")
            .then()
            .statusCode(400)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Query field is required"));
    }

    @Test
    default void shouldReturn200WhenOnlyQueryFieldIsPresent(TwakeCalendarGuiceServer server) {
        String requestBody = """
            {
                "query": "note"
            }
            """;

        given()
            .body(requestBody)
            .post("/calendar/api/events/search?limit=30&offset=0")
            .then()
            .statusCode(200)
            .body("_total_hits", equalTo(0));
    }

    @Test
    default void shouldReturn400WhenOrganizerEmailIsInvalid(TwakeCalendarGuiceServer server) {
        String userId = "6053022c9da5ef001f430b43";
        String calendarId = "6053022c9da5ef001f430b43";

        String requestBody = """
            {
                "calendars": [
                    { "userId": "%s", "calendarId": "%s" }
                ],
                "query": "Organizer",
                "organizers": [ "invalid-email" ]
            }
            """.formatted(userId, calendarId);

        given()
            .body(requestBody)
            .post("/calendar/api/events/search?limit=30&offset=0")
            .then()
            .statusCode(400)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Invalid organizer email address"));
    }

    @Test
    default void shouldReturn400WhenAttendeeEmailIsInvalid(TwakeCalendarGuiceServer server) {
        String userId = "6053022c9da5ef001f430b43";
        String calendarId = "6053022c9da5ef001f430b43";

        String requestBody = """
            {
                "calendars": [
                    { "userId": "%s", "calendarId": "%s" }
                ],
                "query": "Attendee",
                "attendees": [ "invalid-email" ]
            }
            """.formatted(userId, calendarId);

        given()
            .body(requestBody)
            .post("/calendar/api/events/search?limit=30&offset=0")
            .then()
            .statusCode(400)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Invalid attendee email address"));
    }

    @Test
    default void shouldReturn400WhenUserIdIsMissing(TwakeCalendarGuiceServer server) {
        String calendarId = "6053022c9da5ef001f430b43";

        String requestBody = """
        {
            "calendars": [
                { "calendarId": "%s" }
            ],
            "query": "test"
        }
        """.formatted(calendarId);

        given()
            .body(requestBody)
            .post("/calendar/api/events/search?limit=10&offset=0")
            .then()
            .statusCode(400)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("userId field is missing"));
    }

    @Test
    default void shouldReturn400WhenCalendarIdIsMissing(TwakeCalendarGuiceServer server) {
        String userId = "6053022c9da5ef001f430b43";

        String requestBody = """
        {
            "calendars": [
                { "userId": "%s" }
            ],
            "query": "test"
        }
        """.formatted(userId);

        given()
            .body(requestBody)
            .post("/calendar/api/events/search?limit=10&offset=0")
            .then()
            .statusCode(400)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("calendarId field is missing"));
    }

    @Test
    default void shouldReturn400WhenLimitIsInvalid(TwakeCalendarGuiceServer server) {
        String userId = "6053022c9da5ef001f430b43";
        String calendarId = "6053022c9da5ef001f430b43";

        String requestBody = """
        {
            "calendars": [
                { "userId": "%s", "calendarId": "%s" }
            ],
            "query": "test"
        }
        """.formatted(userId, calendarId);

        given()
            .body(requestBody)
            .post("/calendar/api/events/search?limit=invalid&offset=0")
            .then()
            .statusCode(400)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Invalid limit param: invalid"));
    }

    @Test
    default void shouldReturn400WhenLimitIsNegative(TwakeCalendarGuiceServer server) {
        String userId = "6053022c9da5ef001f430b43";
        String calendarId = "6053022c9da5ef001f430b43";

        String requestBody = """
        {
            "calendars": [
                { "userId": "%s", "calendarId": "%s" }
            ],
            "query": "test"
        }
        """.formatted(userId, calendarId);

        given()
            .body(requestBody)
            .post("/calendar/api/events/search?limit=-1&offset=0")
            .then()
            .statusCode(400)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("limit param must be positive"));
    }

    @Test
    default void shouldReturn400WhenOffsetIsInvalid(TwakeCalendarGuiceServer server) {
        String userId = "6053022c9da5ef001f430b43";
        String calendarId = "6053022c9da5ef001f430b43";

        String requestBody = """
        {
            "calendars": [
                { "userId": "%s", "calendarId": "%s" }
            ],
            "query": "test"
        }
        """.formatted(userId, calendarId);

        given()
            .body(requestBody)
            .post("/calendar/api/events/search?limit=10&offset=invalid")
            .then()
            .statusCode(400)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Invalid offset param: invalid"));
    }

    @Test
    default void shouldReturn400WhenOffsetIsNegative(TwakeCalendarGuiceServer server) {
        String userId = "6053022c9da5ef001f430b43";
        String calendarId = "6053022c9da5ef001f430b43";

        String requestBody = """
        {
            "calendars": [
                { "userId": "%s", "calendarId": "%s" }
            ],
            "query": "test"
        }
        """.formatted(userId, calendarId);

        given()
            .body(requestBody)
            .post("/calendar/api/events/search?limit=10&offset=-1")
            .then()
            .statusCode(400)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("offset param must be non-negative"));
    }
}

