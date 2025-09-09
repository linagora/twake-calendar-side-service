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

import static com.linagora.calendar.app.restapi.routes.ImportRouteTest.mailSenderConfigurationFunction;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RedirectConfig.redirectConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.utils.GuiceProbe;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.app.restapi.routes.PeopleSearchRouteTest.ResourceProbe;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalendarUtil;
import com.linagora.calendar.dav.DavCalendarObject;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.Fixture;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.SabreDavProvisioningService;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceId;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;

public class ResourceParticipationRouteTest {
    private static final Domain TEST_DOMAIN = Domain.of(SabreDavProvisioningService.DOMAIN);
    private static final String DEFAULT_USER_PASSWORD = "secret";

    @RegisterExtension
    @Order(1)
    static SabreDavExtension SABRE_DAV_EXTENSION = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    @RegisterExtension
    @Order(2)
    static final MockSmtpServerExtension SMTP_EXTENSION = new MockSmtpServerExtension();

    @RegisterExtension
    @Order(3)
    static final TwakeCalendarExtension TCALENDAR_EXTENSION = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(SABRE_DAV_EXTENSION),
        binder -> {
            Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding().to(ResourceProbe.class);
            binder.bind(MailSenderConfiguration.class)
                .toInstance(mailSenderConfigurationFunction.apply(SMTP_EXTENSION));
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private OpenPaaSUser attendee;
    private OpenPaaSUser organizer;
    private OpenPaaSUser admin;
    private Resource resource;

    private int restApiPort;
    private DavTestHelper davTestHelper;
    private RequestSpecification adminRequestSpec;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws Exception {
        attendee = SABRE_DAV_EXTENSION.newTestUser(Optional.of("attendee"));
        organizer = SABRE_DAV_EXTENSION.newTestUser(Optional.of("organizer"));
        admin = SABRE_DAV_EXTENSION.newTestUser(Optional.of("admin"));
        resource = server.getProbe(ResourceProbe.class).save(admin, "projector toshiba", "projector");

        davTestHelper = new DavTestHelper(SABRE_DAV_EXTENSION.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        server.getProbe(CalendarDataProbe.class).addUserToRepository(attendee.username(), DEFAULT_USER_PASSWORD);
        server.getProbe(CalendarDataProbe.class).addUserToRepository(organizer.username(), DEFAULT_USER_PASSWORD);
        server.getProbe(CalendarDataProbe.class).addUserToRepository(admin.username(), DEFAULT_USER_PASSWORD);

        restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();

        adminRequestSpec = buildAdminRequestSpec(admin.username().asString(), DEFAULT_USER_PASSWORD, restApiPort);
    }

    static Stream<PartStat> validPartStats() {
        return Stream.of(PartStat.DECLINED, PartStat.ACCEPTED);
    }

    @ParameterizedTest
    @MethodSource("validPartStats")
    void shouldReturn302WhenAdminUpdatesParticipationWithValidStatus(PartStat partStat) {
        // Given
        ResourceId resourceId = resource.id();
        String eventUid = UUID.randomUUID().toString();
        String eventPathId = createEventAndGetEventPathId(resource.id(), eventUid);
        String endpoint = buildParticipationEndpoint(resourceId, eventPathId, partStat);

        // When / Then
        given(adminRequestSpec)
            .when()
            .get(endpoint)
        .then()
            .statusCode(302)
            .header("Location", equalTo("https://e-calendrier.avocat.fr/#/calendar"));
    }

    @Test
    void shouldUpdatePartStatOnSabreDavWhenAdminUpdatesParticipation() {
        // Given
        ResourceId resourceId = resource.id();
        String resourceEmail = Username.fromLocalPartWithDomain(resourceId.value(), TEST_DOMAIN).asString();
        String eventUid = UUID.randomUUID().toString();
        String eventPathId = createEventAndGetEventPathId(resource.id(), eventUid);
        String endpoint = buildParticipationEndpoint(resourceId, eventPathId, PartStat.ACCEPTED);

        given(adminRequestSpec)
            .when()
            .get(endpoint)
        .then()
            .statusCode(302);

        Fixture.awaitAtMost.untilAsserted(() -> {
            VEvent vEvent = getCalendarEvent(organizer, eventUid);
            EventFields.Person resourceAttendee = EventParseUtils.getResources(vEvent).getFirst();
            assertThat(resourceAttendee)
                .extracting(EventFields.Person::partStat, p -> p.email().asString())
                .containsExactly(Optional.of(PartStat.ACCEPTED), resourceEmail);
        });
    }

    @Test
    void shouldReturn404WhenResourceIdNotFound() {
        // create a real calendar event for the real resource
        String eventUid = UUID.randomUUID().toString();
        String eventPathId = createEventAndGetEventPathId(resource.id(), eventUid);

        // Fake resourceId
        ResourceId fakeResourceId = new ResourceId(new ObjectId().toHexString());
        String endpoint = buildParticipationEndpoint(fakeResourceId, eventPathId, PartStat.ACCEPTED);

        given(adminRequestSpec)
            .when()
            .get(endpoint)
        .then()
            .statusCode(404)
            .contentType(JSON)
            .body("error.code", equalTo(404))
            .body("error.message", equalTo("Not Found"))
            .body("error.details", containsString(fakeResourceId.value()));
    }

    @Test
    void shouldReturn403WhenUserIsNotAdmin() {
        // create a real calendar event for the real resource
        ResourceId realResourceId = resource.id();
        String eventUid = UUID.randomUUID().toString();
        String eventPathId = createEventAndGetEventPathId(resource.id(), eventUid);
        String endpoint = buildParticipationEndpoint(realResourceId, eventPathId, PartStat.ACCEPTED);

        // Build request spec with organizer (not an admin of this resource)
        RequestSpecification organizerRequestSpec = buildAdminRequestSpec(
            organizer.username().asString(),
            DEFAULT_USER_PASSWORD,
            restApiPort);

        // When / Then
        given(organizerRequestSpec)
            .when()
            .get(endpoint)
        .then()
            .statusCode(403)
            .contentType(JSON)
            .body("error.code", equalTo(403))
            .body("error.message", equalTo("Forbidden"))
            .body("error.details", containsString("does not have admin permission"));
    }

    @ParameterizedTest
    @MethodSource("invalidPartStats")
    void shouldReturn400WhenStatusIsInvalid(String invalidStatus) {
        // Given: create a real calendar event for the real resource
        ResourceId realResourceId = resource.id();
        String eventUid = UUID.randomUUID().toString();
        String eventPathId = createEventAndGetEventPathId(resource.id(), eventUid);

        // Use invalid status in endpoint
        String endpoint = String.format(
            "/calendar/api/resources/%s/%s/participation?referrer=email&status=%s",
            realResourceId.value(),
            eventPathId,
            invalidStatus);

        // When / Then
        given(adminRequestSpec)
            .when()
            .get(endpoint)
        .then()
            .statusCode(400)
            .contentType(JSON)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", containsString("Missing or invalid partStat parameter"));
    }

    @ParameterizedTest
    @MethodSource("missingParamsEndpoints")
    void shouldReturn400WhenMissingRequiredParams(String endpoint) {
        given(adminRequestSpec)
            .when()
            .get(endpoint)
        .then()
            .statusCode(400)
            .contentType(JSON)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"));
    }

    @Test
    void shouldReturn404WhenEventPathIdDoesNotBelongToResource(TwakeCalendarGuiceServer server) {
        // Given: create a real event for resource A
        ResourceId resourceIdA = resource.id();
        String resourceEmailA = Username.fromLocalPartWithDomain(resourceIdA.value(), TEST_DOMAIN).asString();
        String eventUidA = UUID.randomUUID().toString();

        davTestHelper.upsertCalendar(organizer, generateCalendarData(eventUidA, resourceEmailA), eventUidA);
        Fixture.awaitAtMost.untilAsserted(() ->
            assertThat(davTestHelper.findFirstEventId(resourceIdA, resource.domain())).isPresent());

        String eventPathIdA = davTestHelper.findFirstEventId(resourceIdA, resource.domain()).get();

        // Create a second resource B (different from A)
        Resource anotherResource = server.getProbe(ResourceProbe.class)
            .save(admin, "meeting-room", "room");
        ResourceId resourceIdB = anotherResource.id();

        // Use resource B in the request but eventPathId from resource A
        String endpoint = buildParticipationEndpoint(resourceIdB, eventPathIdA, PartStat.ACCEPTED);

        // When / Then: server should fail with 500 (internal error)
        given(adminRequestSpec)
            .when()
            .get(endpoint)
        .then()
            .statusCode(404)
            .contentType(JSON)
            .body("error.code", equalTo(404))
            .body("error.message", equalTo("Not Found"))
            .body("error.details", containsString("Calendar event not found"));
    }

    @Test
    void shouldReturn302WhenUpdatingSameParticipationMultipleTimes() {
        // Given: create a real calendar event for the real resource
        ResourceId resourceId = resource.id();
        String resourceEmail = Username.fromLocalPartWithDomain(resourceId.value(), TEST_DOMAIN).asString();
        String eventUid = UUID.randomUUID().toString();

        String eventPathId = createEventAndGetEventPathId(resource.id(), eventUid);
        String endpoint = buildParticipationEndpoint(resourceId, eventPathId, PartStat.ACCEPTED);

        given(adminRequestSpec)
            .when()
            .get(endpoint)
        .then()
            .statusCode(302);

        Fixture.awaitAtMost.untilAsserted(() -> {
            VEvent vEvent = getCalendarEvent(organizer, eventUid);
            EventFields.Person resourceAttendee = EventParseUtils.getResources(vEvent).getFirst();
            assertThat(resourceAttendee)
                .extracting(EventFields.Person::partStat, p -> p.email().asString())
                .containsExactly(Optional.of(PartStat.ACCEPTED), resourceEmail);
        });

        // When / Then: call the same request multiple times
        for (int i = 0; i < 2; i++) {
            given(adminRequestSpec)
                .when()
                .get(endpoint)
            .then()
                .statusCode(302);
        }
    }

    @Test
    void shouldUpdatePartStatWhenCalledWithDifferentValidStatuses() {
        // Given: create a real calendar event for the real resource
        ResourceId resourceId = resource.id();
        String resourceEmail = Username.fromLocalPartWithDomain(resourceId.value(), TEST_DOMAIN).asString();
        String eventUid = UUID.randomUUID().toString();

        String eventPathId = createEventAndGetEventPathId(resource.id(), eventUid);

        // Step 1: update to ACCEPTED
        String acceptEndpoint = buildParticipationEndpoint(resourceId, eventPathId, PartStat.ACCEPTED);
        given(adminRequestSpec)
            .when()
            .get(acceptEndpoint)
        .then()
            .statusCode(302);

        Fixture.awaitAtMost.untilAsserted(() -> {
            VEvent vEvent = getCalendarEvent(organizer, eventUid);
            EventFields.Person resourceAttendee = EventParseUtils.getResources(vEvent).getFirst();
            assertThat(resourceAttendee)
                .extracting(EventFields.Person::partStat, p -> p.email().asString())
                .containsExactly(Optional.of(PartStat.ACCEPTED), resourceEmail);
        });

        // Step 2: update to DECLINED
        String declineEndpoint = buildParticipationEndpoint(resourceId, eventPathId, PartStat.DECLINED);
        given(adminRequestSpec)
            .when()
            .get(declineEndpoint)
        .then()
            .statusCode(302);

        Fixture.awaitAtMost.untilAsserted(() -> {
            VEvent vEvent = getCalendarEvent(organizer, eventUid);
            EventFields.Person resourceAttendee = EventParseUtils.getResources(vEvent).getFirst();
            assertThat(resourceAttendee)
                .extracting(EventFields.Person::partStat, p -> p.email().asString())
                .containsExactly(Optional.of(PartStat.DECLINED), resourceEmail);
        });
    }

    @Test
    void shouldReturn401WhenNoAuthenticationProvided() {
        // Given: create a real calendar event for the real resource
        ResourceId resourceId = resource.id();
        String eventUid = UUID.randomUUID().toString();

        String eventPathId = createEventAndGetEventPathId(resource.id(), eventUid);
        String endpoint = buildParticipationEndpoint(resourceId, eventPathId, PartStat.ACCEPTED);

        // When / Then: call API without authentication
        given()
            .port(restApiPort)
            .accept(JSON)
            .contentType(JSON)
        .when()
            .get(endpoint)
        .then()
            .statusCode(401);
    }

    static Stream<String> missingParamsEndpoints() {
        return Stream.of(
            // missing status
            String.format("/calendar/api/resources/%s/%s/participation?referrer=email",
                "aaaaaaaaaaaaaaaaaaaaaaaa", "fake-event-id"),
            // missing referrer
            String.format("/calendar/api/resources/%s/%s/participation?status=ACCEPTED",
                "aaaaaaaaaaaaaaaaaaaaaaaa", "fake-event-id"),

            // invalid referer
            String.format( "/calendar/api/resources/%s/%s/participation?referrer=mobile&status=ACCEPTED",
                "aaaaaaaaaaaaaaaaaaaaaaaa", "fake-event-id")
        );
    }

    static Stream<String> invalidPartStats() {
        return Stream.of("INVALID", "TENTATIVE", "NEEDS-ACTION", "MAYBE", "ANYTHING_ELSE");
    }

    private String generateCalendarData(String eventUid, String resourceEmail) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        String startDateTime = LocalDateTime.now().plusDays(3).format(formatter);
        String endDateTime = LocalDateTime.now().plusDays(3).plusHours(1).format(formatter);

        String icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.2.2//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:{dtStamp}Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:{startDateTime}
            DTEND;TZID=Asia/Ho_Chi_Minh:{endDateTime}
            SUMMARY:Twake Calendar - Sprint planning #04
            ORGANIZER;CN=Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat};CN=Attendee:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=TENTATIVE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;
             CN=Resource;SCHEDULE-STATUS=5.1:mailto:{resourceEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.username().asString())
            .replace("{attendeeEmail}", attendee.username().asString())
            .replace("{resourceEmail}", resourceEmail)
            .replace("{startDateTime}", startDateTime)
            .replace("{endDateTime}", endDateTime)
            .replace("{dtStamp}", LocalDateTime.now().format(formatter))
            .replace("{partStat}", PartStat.NEEDS_ACTION.getValue());

        return CalendarUtil.parseIcs(icalData).toString();
    }

    private String buildParticipationEndpoint(ResourceId resourceId, String eventPathId, PartStat partStat) {
        return String.format("/calendar/api/resources/%s/%s/participation?referrer=email&status=%s",
            resourceId.value(), eventPathId, partStat.getValue());
    }

    private RequestSpecification buildAdminRequestSpec(String username, String password, int port) {
        PreemptiveBasicAuthScheme auth = new PreemptiveBasicAuthScheme();
        auth.setUserName(username);
        auth.setPassword(password);

        return new RequestSpecBuilder()
            .setPort(port)
            .setAuth(auth)
            .setAccept(JSON)
            .setContentType(JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8))
                .redirect(redirectConfig().followRedirects(false)))
            .build();
    }

    private VEvent getCalendarEvent(OpenPaaSUser openPaaSUser, String eventUid) throws Exception {
        CalDavClient calDavClient = new CalDavClient(SABRE_DAV_EXTENSION.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        URI organizerEventHref = URI.create(String.format("/calendars/%s/%s/%s.ics", openPaaSUser.id().value(), openPaaSUser.id().value(), eventUid));
        return (VEvent) calDavClient.fetchCalendarEvent(openPaaSUser.username(), organizerEventHref)
            .map(DavCalendarObject::calendarData)
            .map(calendar -> calendar.getComponent(Component.VEVENT)
                .orElseThrow(() -> new IllegalStateException("No VEVENT found in calendar")))
            .block();
    }

    private String createEventAndGetEventPathId(ResourceId resourceId, String eventUid) {
        String resourceEmail = Username.fromLocalPartWithDomain(resourceId.value(), TEST_DOMAIN).asString();
        davTestHelper.upsertCalendar(organizer, generateCalendarData(eventUid, resourceEmail), eventUid);
        Fixture.awaitAtMost.untilAsserted(() -> assertThat(davTestHelper.findFirstEventId(resourceId, resource.domain())).isPresent());
        return davTestHelper.findFirstEventId(resourceId, resource.domain()).get();
    }
}
