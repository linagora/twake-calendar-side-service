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

import static com.linagora.calendar.app.AppTestHelper.OPENSEARCH_TEST_MODULE;
import static com.linagora.calendar.dav.DavModuleTestHelper.FROM_SABRE_EXTENSION;
import static com.linagora.calendar.dav.Fixture.awaitAtMost;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.empty;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.james.backends.opensearch.DockerOpenSearchExtension;
import org.apache.james.core.Username;
import org.apache.james.utils.GuiceProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import com.linagora.calendar.app.restapi.routes.PeopleSearchRouteTest.ResourceProbe;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.dto.SubscribedCalendarRequest;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.eventsearch.EventUid;
import com.linagora.calendar.storage.model.Resource;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

class OpensearchDavCalendarSearchRouteSharedCalendarTest {
    private static final String PASSWORD = "secret";

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = SabreDavExtension.perClass();

    @RegisterExtension
    @Order(2)
    static DockerOpenSearchExtension openSearchExtension = new DockerOpenSearchExtension();

    @RegisterExtension
    @Order(3)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB)
            .calendarEventSearchChoice(TwakeCalendarConfiguration.CalendarEventSearchChoice.OPENSEARCH),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        OPENSEARCH_TEST_MODULE.apply(openSearchExtension),
        binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
            .addBinding()
            .to(ResourceProbe.class));

    private static DavTestHelper davTestHelper;
    private static CalDavClient calDavClient;

    private OpenPaaSUser alice;
    private OpenPaaSUser bob;
    private RequestSpecification bobRequestSpecification;

    @BeforeAll
    static void beforeAll() throws Exception {
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        alice = sabreDavExtension.newTestUser();
        bob = sabreDavExtension.newTestUser();
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(alice.username().getDomainPart().orElseThrow());
        calendarDataProbe.addUserToRepository(alice.username(), PASSWORD);
        calendarDataProbe.addDomain(bob.username().getDomainPart().orElseThrow());
        calendarDataProbe.addUserToRepository(bob.username(), PASSWORD);

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setBasePath("")
            .setAccept(ContentType.JSON)
            .setContentType(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .build();

        PreemptiveBasicAuthScheme bobAuth = new PreemptiveBasicAuthScheme();
        bobAuth.setUserName(bob.username().asString());
        bobAuth.setPassword(PASSWORD);
        bobRequestSpecification = new RequestSpecBuilder()
            .addRequestSpecification(RestAssured.requestSpecification)
            .setAuth(bobAuth)
            .build();
    }

    @Test
    void searchShouldReturnSourceCalendarEventForSubscribedCalendar() {
        // Given Alice owns a readable source calendar Alice/Alice containing an indexed event.
        CalendarURL sourceCalendar = CalendarURL.from(alice.id());
        EventUid eventUid = new EventUid("event-subscription-" + UUID.randomUUID());
        String summary = "subscribed searchable event " + UUID.randomUUID();
        davTestHelper.upsertCalendar(alice, generateCalendarData(eventUid, summary, alice), eventUid);
        calDavClient.updateCalendarAcl(alice.username(), sourceCalendar, CalDavClient.PublicRight.READ).block();

        CalendarURL subscribedCalendar = davTestHelper.subscribeToSharedCalendar(bob, SubscribedCalendarRequest.builder()
            .id("subscribed-" + UUID.randomUUID())
            .sourceUserId(alice.id().value())
            .sourceCalendarId(alice.id().value())
            .name("Alice subscribed calendar")
            .color("#123456")
            .readOnly(true)
            .build());

        // When Bob searches using either the subscribed mirror Bob/S or the source calendar Alice/Alice.
        // Then the route resolves the requested visible calendar to the source calendar.
        List.of(subscribedCalendar, sourceCalendar).forEach(requestedCalendar ->
            awaitAtMost.untilAsserted(() -> given(bobRequestSpecification)
                .body(searchRequest(requestedCalendar, summary))
                .post("/calendar/api/events/search")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("_total_hits", equalTo(1))
                .body("_embedded.events._links.self.href", contains(sourceCalendar.asUri().toASCIIString() + "/" + eventUid.value() + ".ics"))
                .body("_embedded.events.data.uid", contains(eventUid.value()))
                .body("_embedded.events.data.userId", contains(sourceCalendar.base().value()))
                .body("_embedded.events.data.calendarId", contains(sourceCalendar.calendarId().value()))));
    }

    @Test
    void searchShouldReturnSourceCalendarEventForDelegatedCalendar() {
        // Given Alice delegates her source calendar Alice/Alice containing an indexed event to Bob.
        CalendarURL sourceCalendar = CalendarURL.from(alice.id());
        EventUid eventUid = new EventUid("event-delegation-" + UUID.randomUUID());
        String summary = "delegated searchable event " + UUID.randomUUID();
        davTestHelper.upsertCalendar(alice, generateCalendarData(eventUid, summary, alice), eventUid);
        davTestHelper.grantDelegation(alice, sourceCalendar, bob, "dav:read");
        CalendarURL delegatedCalendar = findMirrorCalendar(bob);

        // When Bob searches using either the delegated mirror Bob/D or the source calendar Alice/Alice.
        // Then the route resolves the requested visible calendar to the source calendar.
        List.of(delegatedCalendar, sourceCalendar).forEach(requestedCalendar ->
            awaitAtMost.untilAsserted(() -> given(bobRequestSpecification)
                .body(searchRequest(requestedCalendar, summary))
                .post("/calendar/api/events/search")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("_total_hits", equalTo(1))
                .body("_embedded.events._links.self.href", contains(sourceCalendar.asUri().toASCIIString() + "/" + eventUid.value() + ".ics"))
                .body("_embedded.events.data.uid", contains(eventUid.value()))
                .body("_embedded.events.data.userId", contains(sourceCalendar.base().value()))
                .body("_embedded.events.data.calendarId", contains(sourceCalendar.calendarId().value()))));
    }

    @Test
    void searchShouldFilterPrivateSourceCalendarForOtherUser() {
        // Given Alice owns a private source calendar Alice/Alice containing an indexed event.
        CalendarURL sourceCalendar = CalendarURL.from(alice.id());
        EventUid eventUid = new EventUid("event-private-" + UUID.randomUUID());
        String summary = "private searchable event " + UUID.randomUUID();
        davTestHelper.upsertCalendar(alice, generateCalendarData(eventUid, summary, alice), eventUid);
        calDavClient.updateCalendarAcl(alice.username(), sourceCalendar, CalDavClient.PublicRight.HIDE_ALL_EVENT).block();

        // When Alice searches her own source calendar.
        // Then the event is found, proving it has been indexed.
        awaitAtMost.untilAsserted(() -> given()
            .auth().preemptive()
            .basic(alice.username().asString(), PASSWORD)
            .body(searchRequest(sourceCalendar, summary))
            .post("/calendar/api/events/search")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("_total_hits", equalTo(1))
            .body("_embedded.events._links.self.href", contains(sourceCalendar.asUri().toASCIIString() + "/" + eventUid.value() + ".ics"))
            .body("_embedded.events.data.uid", contains(eventUid.value())));

        // When Bob searches Alice's private source calendar.
        // Then the route filters the unreadable calendar and returns no event.
        awaitAtMost.untilAsserted(() -> given(bobRequestSpecification)
            .body(searchRequest(sourceCalendar, summary))
            .post("/calendar/api/events/search")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("_total_hits", equalTo(0))
            .body("_embedded.events", empty()));
    }

    @Test
    void searchShouldReturnEmptyWhenRequestedCalendarListIsEmpty() {
        // Given Alice owns an indexed event in her default calendar Alice/Alice.
        CalendarURL sourceCalendar = CalendarURL.from(alice.id());
        EventUid eventUid = new EventUid("event-empty-calendar-list-" + UUID.randomUUID());
        String summary = "empty calendar list searchable event " + UUID.randomUUID();
        davTestHelper.upsertCalendar(alice, generateCalendarData(eventUid, summary, alice), eventUid);

        // And Alice can find the event from her default calendar.
        awaitAtMost.untilAsserted(() -> given()
            .auth().preemptive()
            .basic(alice.username().asString(), PASSWORD)
            .body(searchRequest(sourceCalendar, summary))
            .post("/calendar/api/events/search")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("_total_hits", equalTo(1))
            .body("_embedded.events.data.uid", contains(eventUid.value())));

        // When Alice searches all terms with an empty requested calendar list.
        // Then the route returns no result instead of searching every source calendar.
        given()
            .auth().preemptive()
            .basic(alice.username().asString(), PASSWORD)
            .body("""
                {
                    "calendars": [],
                    "query": ""
                }
                """)
            .post("/calendar/api/events/search")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("_total_hits", equalTo(0))
            .body("_embedded.events", empty());
    }

    @Test
    void searchShouldFilterSubscribedCalendarEventAfterPublicReadRevoke() {
        // Given Alice owns a public readable source calendar Alice/Alice containing an indexed event.
        CalendarURL sourceCalendar = CalendarURL.from(alice.id());
        EventUid eventUid = new EventUid("event-subscription-revoked-" + UUID.randomUUID());
        String summary = "revoked subscription searchable event " + UUID.randomUUID();
        davTestHelper.upsertCalendar(alice, generateCalendarData(eventUid, summary, alice), eventUid);
        calDavClient.updateCalendarAcl(alice.username(), sourceCalendar, CalDavClient.PublicRight.READ).block();

        CalendarURL subscribedCalendar = davTestHelper.subscribeToSharedCalendar(bob, SubscribedCalendarRequest.builder()
            .id("subscribed-revoked-" + UUID.randomUUID())
            .sourceUserId(alice.id().value())
            .sourceCalendarId(alice.id().value())
            .name("Alice subscribed calendar revoked")
            .color("#123456")
            .readOnly(true)
            .build());

        // When Bob searches using the subscribed mirror Bob/S.
        // Then the event is found while the source calendar is public readable.
        awaitAtMost.untilAsserted(() -> given(bobRequestSpecification)
            .body(searchRequest(subscribedCalendar, summary))
            .post("/calendar/api/events/search")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("_total_hits", equalTo(1))
            .body("_embedded.events._links.self.href", contains(sourceCalendar.asUri().toASCIIString() + "/" + eventUid.value() + ".ics"))
            .body("_embedded.events.data.uid", contains(eventUid.value())));

        calDavClient.updateCalendarAcl(alice.username(), sourceCalendar, CalDavClient.PublicRight.HIDE_ALL_EVENT).block();

        // When Alice revokes public read access.
        // Then Bob's subscribed mirror no longer resolves to searchable source events.
        awaitAtMost.untilAsserted(() -> given(bobRequestSpecification)
            .body(searchRequest(subscribedCalendar, summary))
            .post("/calendar/api/events/search")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("_total_hits", equalTo(0))
            .body("_embedded.events", empty()));
    }

    @Test
    void searchShouldFilterDelegatedCalendarEventAfterDelegationRevoke() {
        // Given Alice delegates a dedicated source calendar Alice/D to Bob.
        String sourceCalendarId = "delegated-source-" + UUID.randomUUID();
        CalendarURL sourceCalendar = new CalendarURL(alice.id(), new OpenPaaSId(sourceCalendarId));
        calDavClient.createNewCalendar(alice.username(), alice.id(), new CalDavClient.NewCalendar(
            sourceCalendarId,
            "Delegated source calendar",
            "#123456",
            "Delegated source calendar for search test")).block();

        EventUid eventUid = new EventUid("event-delegation-revoked-" + UUID.randomUUID());
        String summary = "revoked delegation searchable event " + UUID.randomUUID();
        davTestHelper.upsertCalendar(alice.username(),
            URI.create(sourceCalendar.asUri().toASCIIString() + "/" + eventUid.value() + ".ics"),
            generateCalendarData(eventUid, summary, alice)).block();
        davTestHelper.grantDelegation(alice, sourceCalendar, bob, "dav:read");
        CalendarURL delegatedCalendar = findMirrorCalendar(bob);

        // When Bob searches using the delegated mirror Bob/D.
        // Then the event is found while the delegation exists.
        awaitAtMost.untilAsserted(() -> given(bobRequestSpecification)
            .body(searchRequest(delegatedCalendar, summary))
            .post("/calendar/api/events/search")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("_total_hits", equalTo(1))
            .body("_embedded.events._links.self.href", contains(sourceCalendar.asUri().toASCIIString() + "/" + eventUid.value() + ".ics"))
            .body("_embedded.events.data.uid", contains(eventUid.value())));

        davTestHelper.revokeDelegation(alice, sourceCalendar, bob);

        // When Alice revokes Bob's delegation.
        // Then Bob's delegated mirror no longer resolves to searchable source events.
        awaitAtMost.untilAsserted(() -> given(bobRequestSpecification)
            .body(searchRequest(delegatedCalendar, summary))
            .post("/calendar/api/events/search")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("_total_hits", equalTo(0))
            .body("_embedded.events", empty()));
    }

    @Test
    void searchShouldReturnResourceCalendarEventWhenRequesterIsResourceAdministrator(TwakeCalendarGuiceServer server) {
        // Given Bob has DAV read/write rights on a resource calendar R/R containing an indexed event.
        Resource resource = server.getProbe(ResourceProbe.class)
            .save(alice, "search admin resource " + UUID.randomUUID(), "projector", List.of());
        CalendarURL resourceCalendar = CalendarURL.from(resource.id().asOpenPaaSId());
        EventUid eventUid = new EventUid("event-resource-admin-" + UUID.randomUUID());
        String summary = "resource admin searchable event " + UUID.randomUUID();
        calDavClient.grantReadWriteRights(resource.domain(), resource.id(), List.of(bob.username())).block();
        davTestHelper.upsertCalendar(alice, generateCalendarData(eventUid, summary, alice, resource), eventUid);
        String resourceEventId = awaitAtMost.until(
            () -> davTestHelper.findFirstEventId(resource.id(), resource.domain()),
            Optional::isPresent).get();

        // When Bob searches using the source resource calendar R/R.
        // Then the route keeps the readable source resource calendar.
        awaitAtMost.untilAsserted(() -> given(bobRequestSpecification)
            .body(searchRequest(resourceCalendar, summary))
            .post("/calendar/api/events/search")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("_total_hits", equalTo(1))
            .body("_embedded.events._links.self.href", contains(resourceCalendar.asUri().toASCIIString() + "/" + resourceEventId + ".ics"))
            .body("_embedded.events.data.uid", contains(eventUid.value()))
            .body("_embedded.events.data.userId", contains(resourceCalendar.base().value()))
            .body("_embedded.events.data.calendarId", contains(resourceCalendar.calendarId().value())));
    }

    @Test
    void searchShouldReturnSubscribedResourceCalendarEventWhenRequestedCalendarIsSubscribeMirror(TwakeCalendarGuiceServer server) {
        // Given Bob subscribes to a resource calendar R/R containing an indexed event.
        Resource resource = server.getProbe(ResourceProbe.class)
            .save(alice, "search subscribed resource " + UUID.randomUUID(), "projector", List.of());
        CalendarURL resourceCalendar = CalendarURL.from(resource.id().asOpenPaaSId());
        EventUid eventUid = new EventUid("event-resource-subscription-" + UUID.randomUUID());
        String summary = "resource subscription searchable event " + UUID.randomUUID();
        davTestHelper.upsertCalendar(alice, generateCalendarData(eventUid, summary, alice, resource), eventUid);
        String resourceEventId = awaitAtMost.until(
            () -> davTestHelper.findFirstEventId(resource.id(), resource.domain()),
            Optional::isPresent).get();

        CalendarURL subscribedResourceCalendar = davTestHelper.subscribeToSharedCalendar(bob, SubscribedCalendarRequest.builder()
            .id("subscribed-resource-" + UUID.randomUUID())
            .sourceUserId(resource.id().value())
            .sourceCalendarId(resource.id().value())
            .name("Subscribed resource calendar")
            .color("#123456")
            .readOnly(true)
            .build());

        // When Bob searches using the subscribed resource mirror Bob/S.
        // Then the route resolves the requested visible resource calendar to the source resource calendar.
        awaitAtMost.untilAsserted(() -> given(bobRequestSpecification)
            .body(searchRequest(subscribedResourceCalendar, summary))
            .post("/calendar/api/events/search")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("_total_hits", equalTo(1))
            .body("_embedded.events._links.self.href", contains(resourceCalendar.asUri().toASCIIString() + "/" + resourceEventId + ".ics"))
            .body("_embedded.events.data.uid", contains(eventUid.value()))
            .body("_embedded.events.data.userId", contains(resourceCalendar.base().value()))
            .body("_embedded.events.data.calendarId", contains(resourceCalendar.calendarId().value())));
    }

    private String generateCalendarData(EventUid eventUid, String summary, OpenPaaSUser organizer) {
        return """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            CALSCALE:GREGORIAN\r
            PRODID:-//SabreDAV//SabreDAV 3.2.2//EN\r
            BEGIN:VEVENT\r
            UID:{eventUid}\r
            DTSTART:20260101T100000Z\r
            DTEND:20260101T110000Z\r
            CLASS:PUBLIC\r
            SUMMARY:{summary}\r
            DESCRIPTION:Search source resolution test event\r
            ORGANIZER:mailto:{organizer}\r
            DTSTAMP:20251231T100000Z\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.replace("{eventUid}", eventUid.value())
            .replace("{summary}", summary)
            .replace("{organizer}", organizer.username().asString());
    }

    private String generateCalendarData(EventUid eventUid, String summary, OpenPaaSUser organizer, Resource resource) {
        String resourceEmail = Username.fromLocalPartWithDomain(resource.id().value(), organizer.username().getDomainPart().orElseThrow()).asString();

        return """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            CALSCALE:GREGORIAN\r
            PRODID:-//SabreDAV//SabreDAV 3.2.2//EN\r
            BEGIN:VEVENT\r
            UID:{eventUid}\r
            DTSTART:20260101T100000Z\r
            DTEND:20260101T110000Z\r
            CLASS:PUBLIC\r
            SUMMARY:{summary}\r
            DESCRIPTION:Search source resolution test event\r
            ORGANIZER:mailto:{organizer}\r
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizer}\r
            ATTENDEE;PARTSTAT=TENTATIVE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=Projector:mailto:{resource}\r
            DTSTAMP:20251231T100000Z\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.replace("{eventUid}", eventUid.value())
            .replace("{summary}", summary)
            .replace("{organizer}", organizer.username().asString())
            .replace("{resource}", resourceEmail);
    }

    private CalendarURL findMirrorCalendar(OpenPaaSUser user) {
        return awaitAtMost.until(() -> calDavClient.findUserCalendarList(user)
            .map(response -> response.calendars()
                .keySet()
                .stream()
                .filter(calendarURL -> !calendarURL.equals(CalendarURL.from(user.id())))
                .findFirst())
            .block(), Optional::isPresent).get();
    }

    private String searchRequest(CalendarURL calendarURL, String query) {
        return """
            {
                "calendars": [
                    { "userId": "%s", "calendarId": "%s" }
                ],
                "query": "%s"
            }
            """.formatted(calendarURL.base().value(), calendarURL.calendarId().value(), query);
    }

}
