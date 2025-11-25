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
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.awaitility.Durations.ONE_HUNDRED_MILLISECONDS;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.james.backends.opensearch.DockerOpenSearchExtension;
import org.apache.james.utils.WebAdminGuiceProbe;
import org.awaitility.Awaitility;
import org.awaitility.Durations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.OpenPaaSUser;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import net.javacrumbs.jsonunit.core.Option;

class OpensearchDavCalendarSearchRouteTest implements CalendarSearchRouteContract{

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

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
        AppTestHelper.EVENT_BUS_BY_PASS_MODULE,
        FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        OPENSEARCH_TEST_MODULE.apply(openSearchExtension));

    private static DavTestHelper davTestHelper;

    @BeforeAll
    static void beforeAll() throws Exception {
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @DisplayName("Should response index events in OpenSearch when created via CalDAV")
    @Test
    void shouldResponseIndexEventInOpenSearchWhenCreatedViaCalDAV(TwakeCalendarGuiceServer server) throws Exception {
        OpenPaaSUser openPaasUser = sabreDavExtension.newTestUser();

        server.getProbe(CalendarDataProbe.class).addDomain(openPaasUser.username().getDomainPart().get());
        server.getProbe(CalendarDataProbe.class).addUserToRepository(openPaasUser.username(), PASSWORD);

        String eventUid = UUID.randomUUID().toString();
        String summary = "Meeting Summary";
        String organizer = openPaasUser.username().asString();
        OpenPaaSUser attendee1 = sabreDavExtension.newTestUser();
        OpenPaaSUser attendee2 = sabreDavExtension.newTestUser();
        OpenPaaSUser attendee3 = sabreDavExtension.newTestUser();

        String calendarData =
            """
                BEGIN:VCALENDAR\r
                VERSION:2.0\r
                CALSCALE:GREGORIAN\r
                PRODID:-//SabreDAV//SabreDAV 3.2.2//EN\r
                X-WR-CALNAME:#default\r
                BEGIN:VTIMEZONE\r
                TZID:Asia/Jakarta\r
                BEGIN:STANDARD\r
                TZOFFSETFROM:+0700\r
                TZOFFSETTO:+0700\r
                TZNAME:WIB\r
                DTSTART:19700101T000000\r
                END:STANDARD\r
                END:VTIMEZONE\r
                BEGIN:VEVENT\r
                UID:{eventUid}\r
                TRANSP:OPAQUE\r
                DTSTART;VALUE=DATE:20250512\r
                DTEND;VALUE=DATE:20250515\r
                CLASS:PUBLIC\r
                SUMMARY:{summary}\r
                LOCATION:Meeting Room 101\r
                DESCRIPTION:Detailed meeting description\r
                ORGANIZER;CN=John1 Doe1:mailto:{organizer}\r
                ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;\
                CUTYPE=INDIVIDUAL;CN=John2 Doe2;SCHEDULE-STATUS=1.1:\
                mailto:{attendee1}\r
                ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;\
                CUTYPE=INDIVIDUAL;CN=John3 Doe3;SCHEDULE-STATUS=1.1:\
                mailto:{attendee2}\r
                ATTENDEE;PARTSTAT=TENTATIVE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;\
                CUTYPE=RESOURCE;CN=Test resource;SCHEDULE-STATUS=5.1:\
                mailto:{attendee3}\r
                ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;\
                CUTYPE=INDIVIDUAL:mailto:{organizer}\r
                DTSTAMP:20250515T091619Z\r
                BEGIN:VALARM\r
                TRIGGER:-PT30M\r
                ACTION:EMAIL\r
                ATTENDEE:mailto:{organizer}\r
                SUMMARY:Full field calendar\r
                DESCRIPTION:This is an automatic alarm sent by OpenPaas\r
                END:VALARM\r
                END:VEVENT\r
                END:VCALENDAR\r
                """.replace("{eventUid}", eventUid)
                .replace("{summary}", summary)
                .replace("{organizer}", organizer)
                .replace("{attendee1}", attendee1.username().asString())
                .replace("{attendee2}", attendee2.username().asString())
                .replace("{attendee3}", attendee3.username().asString());

        davTestHelper.upsertCalendar(openPaasUser, calendarData, eventUid);

        String requestBody = """
            {
                "calendars": [
                    { "userId": "%s", "calendarId": "%s" }
                ],
                "query": "%s"
            }
            """.formatted(openPaasUser.id().value(), openPaasUser.id().value(), summary);

        String expectedResponse = """
            {
                 "_links": {
                     "self": {
                         "href": "/calendar/api/events/search?limit=30&offset=0"
                     }
                 },
                 "_total_hits": 1,
                 "_embedded": {
                     "events": [
                         {
                             "_links": {
                                 "self": {
                                     "href": "/calendars/{userId}/{calendarId}/{eventUid}.ics"
                                 }
                             },
                             "data": {
                                 "uid": "{eventUid}",
                                 "summary": "Meeting Summary",
                                 "description": "Detailed meeting description",
                                 "start": "2025-05-12T00:00:00Z",
                                 "end": "2025-05-15T00:00:00Z",
                                 "allDay": true,
                                 "hasResources": true,
                                 "durationInDays": 3,
                                 "isRecurrentMaster": null,
                                 "location": "Meeting Room 101",
                                 "attendees": [
                                     {
                                         "email": "{attendee1}",
                                         "cn": "John2 Doe2"
                                     },
                                     {
                                         "email": "{attendee2}",
                                         "cn": "John3 Doe3"
                                     },
                                     {
                                         "email": "{organizer}",
                                         "cn": null
                                     }
                                 ],
                                 "organizer": {
                                     "email": "{organizer}",
                                     "cn": "John1 Doe1"
                                 },
                                 "resources": [
                                     {
                                         "cn": "Test resource",
                                         "email": "{attendee3}"
                                     }
                                 ],
                                 "userId": "{userId}",
                                 "calendarId": "{calendarId}",
                                 "dtstamp": "2025-05-15T09:16:19Z",
                                 "class": "PUBLIC"
                             }
                         }
                     ]
                 }
             }""".replace("{organizer}", organizer)
            .replace("{attendee1}", attendee1.username().asString())
            .replace("{attendee2}", attendee2.username().asString())
            .replace("{attendee3}", attendee3.username().asString())
            .replace("{eventUid}", eventUid)
            .replace("{userId}", openPaasUser.id().value())
            .replace("{calendarId}", openPaasUser.id().value());

        Awaitility
            .with().pollInterval(ONE_HUNDRED_MILLISECONDS)
            .and().pollDelay(ONE_HUNDRED_MILLISECONDS)
            .await()
            .atMost(Durations.TEN_SECONDS)
            .untilAsserted(() -> {

                String searchResponse = given()
                    .auth().preemptive()
                    .basic(openPaasUser.username().asString(), PASSWORD)
                    .body(requestBody)
                    .post("/calendar/api/events/search")
                .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .extract()
                    .body()
                    .asString();

                assertThatJson(searchResponse)
                    .withOptions(Option.IGNORING_ARRAY_ORDER)
                    .isEqualTo(expectedResponse);
            });
    }

    @Test
    void shouldExposeWebAdminHealthcheck(TwakeCalendarGuiceServer server) {
        String body = given(webAdminSpec(server))
            .when()
            .get("/healthcheck")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                {
                   "status" : "healthy",
                   "checks" : [ {
                     "componentName" : "Guice application lifecycle",
                     "escapedComponentName" : "Guice%20application%20lifecycle",
                     "status" : "healthy",
                     "cause" : null
                   }, {
                     "componentName" : "MongoDB",
                     "escapedComponentName" : "MongoDB",
                     "status" : "healthy",
                     "cause" : null
                   }, {
                     "componentName" : "OpenSearch Backend",
                     "escapedComponentName" : "OpenSearch%20Backend",
                     "status" : "healthy",
                     "cause" : null
                   }, {
                     "componentName" : "RabbitMQ backend",
                     "escapedComponentName" : "RabbitMQ%20backend",
                     "status" : "healthy",
                     "cause" : null
                   }, {
                     "componentName" : "CalendarQueueConsumers",
                     "escapedComponentName" : "CalendarQueueConsumers",
                     "status" : "healthy",
                     "cause" : null
                   }, {
                     "componentName" : "RabbitMQDeadLetterQueueEmptiness",
                     "escapedComponentName" : "RabbitMQDeadLetterQueueEmptiness",
                     "status" : "healthy",
                     "cause" : null
                   } ]
                }
                """);
    }

    private RequestSpecification webAdminSpec(TwakeCalendarGuiceServer server) {
        return new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(WebAdminGuiceProbe.class).getWebAdminPort().getValue())
            .build();
    }
}
