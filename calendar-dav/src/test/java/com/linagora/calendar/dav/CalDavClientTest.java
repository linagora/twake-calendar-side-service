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

package com.linagora.calendar.dav;

import static com.linagora.calendar.dav.CalDavClient.CalDavExportException;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.dav.CalDavClient.NewCalendar;
import com.linagora.calendar.dav.dto.CalendarReportJsonResponse;
import com.linagora.calendar.dav.dto.CalendarReportXmlResponse;
import com.linagora.calendar.dav.dto.CalendarReportXmlResponse.CalendarObject;
import com.linagora.calendar.dav.dto.VCalendarDto;
import com.linagora.calendar.dav.model.CalendarQuery;
import com.linagora.calendar.dav.model.CalendarQuery.TimeRangePropFilter;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.MailboxSessionUtil;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;

public class CalDavClientTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);
    private static DavTestHelper davTestHelper;

    private CalDavClient testee;

    @BeforeAll
    static void setUp() throws SSLException {
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @BeforeEach
    void setupEach() throws Exception {
        testee = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    private OpenPaaSUser createOpenPaaSUser() {
        return sabreDavExtension.newTestUser();
    }

    @Test
    void exportShouldSucceed() {
        OpenPaaSUser openPaaSUser = createOpenPaaSUser();
        String exportPayloadAsString = testee.export(CalendarURL.from(openPaaSUser.id()), MailboxSessionUtil.create(openPaaSUser.username()))
            .map(bytes -> StringUtils.trim(new String(bytes, StandardCharsets.UTF_8)))
            .block();

        assertThat(exportPayloadAsString).startsWith("BEGIN:VCALENDAR");
        assertThat(exportPayloadAsString).endsWith("END:VCALENDAR");
    }

    @Test
    void exportShouldThrowWhenInvalidPath() {
        OpenPaaSUser openPaaSUser = createOpenPaaSUser();

        CalendarURL invalidUrlPath = new CalendarURL(openPaaSUser.id(), new OpenPaaSId("invalid"));

        assertThatThrownBy(() -> testee.export(invalidUrlPath, MailboxSessionUtil.create(openPaaSUser.username())).block())
            .isInstanceOf(CalDavExportException.class)
            .hasMessageContaining("Failed to export calendar");
    }

    @Test
    void exportShouldThrowWhenNotFound() {
        OpenPaaSUser openPaaSUser = createOpenPaaSUser();

        CalendarURL notFoundCalendarURL = CalendarURL.from(new OpenPaaSId(UUID.randomUUID().toString()));

        assertThatThrownBy(() -> testee.export(notFoundCalendarURL, MailboxSessionUtil.create(openPaaSUser.username())).block())
            .isInstanceOf(CalDavExportException.class)
            .hasMessageContaining("Failed to export calendar");
    }

    @Test
    void exportShouldThrowWhenPathNotBelongingToUser() {
        OpenPaaSUser openPaaSUser1 = createOpenPaaSUser();
        OpenPaaSUser openPaaSUser2 = createOpenPaaSUser();

        CalendarURL notBelongingCalendarURL = CalendarURL.from(openPaaSUser1.id());

        assertThatThrownBy(() -> testee.export(notBelongingCalendarURL, MailboxSessionUtil.create(openPaaSUser2.username())).block())
            .isInstanceOf(CalDavExportException.class)
            .hasMessageContaining("Failed to export calendar");
    }

    @Test
    void importCalendarShouldSucceed() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL calendarURL = CalendarURL.from(user.id());

        String uid = UUID.randomUUID().toString();
        String ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Test Event
            RRULE:FREQ=DAILY;COUNT=3
            CLASS:PUBLIC
            ORGANIZER;CN=john doe:mailto:%s
            ATTENDEE;PARTSTAT=accepted;RSVP=false;ROLE=chair;CUTYPE=individual:mailto:%s
            DESCRIPTION:This is a test event
            LOCATION:office
            BEGIN:VALARM
            TRIGGER:-PT5M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:test
            DESCRIPTION:This is an automatic alarm
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid, user.username().asString(), user.username().asString(), user.username().asString());

        // To trigger calendar directory activation
        testee.export(calendarURL, MailboxSessionUtil.create(user.username())).block();

        testee.importCalendar(calendarURL, uid, user.username(), ics.getBytes(StandardCharsets.UTF_8)).block();

        String exportedCalendarString = testee.export(calendarURL, MailboxSessionUtil.create(user.username()))
            .map(bytes -> StringUtils.trim(new String(bytes, StandardCharsets.UTF_8)))
            .block();
        Calendar exportedCalendar = CalendarUtil.parseIcs(exportedCalendarString.getBytes(StandardCharsets.UTF_8));

        Calendar calendar = CalendarUtil.parseIcs(ics.getBytes(StandardCharsets.UTF_8));
        VEvent expected = (VEvent) calendar.getComponent(Component.VEVENT).get();

        assertThat((VEvent) exportedCalendar.getComponent(Component.VEVENT).get()).isEqualTo(expected);
    }

    @Test
    void importCalendarShouldThrowWhenDataContainMultipleEventsWithDifferentUid() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL calendarURL = CalendarURL.from(user.id());

        String uid = UUID.randomUUID().toString();
        String uid2 = UUID.randomUUID().toString();
        String ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:First Event
            END:VEVENT
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            DTSTART:20250103T120000Z
            DTEND:20250103T130000Z
            SUMMARY:Second Event
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid, uid2);

        // To trigger calendar directory activation
        testee.export(calendarURL, MailboxSessionUtil.create(user.username())).block();

        assertThatThrownBy(() -> testee.importCalendar(calendarURL, uid, user.username(), ics.getBytes(StandardCharsets.UTF_8)).block())
            .isInstanceOf(DavClientException.class);
    }

    @Test
    void importCalendarShouldThrowWhenInvalidData() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL calendarURL = CalendarURL.from(user.id());

        String uid = UUID.randomUUID().toString();
        String ics = """
            BEGIN:VCALENDAR
            END:VCALENDAR
            """;

        // To trigger calendar directory activation
        testee.export(calendarURL, MailboxSessionUtil.create(user.username())).block();

        assertThatThrownBy(() -> testee.importCalendar(calendarURL, uid, user.username(), ics.getBytes(StandardCharsets.UTF_8)).block())
            .isInstanceOf(DavClientException.class);
    }

    @Test
    void findUserCalendarsShouldSucceed() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalDavClient.NewCalendar newCalendar = new CalDavClient.NewCalendar(
            "fe71d5c5-7fd3-49be-8895-c79213605154",
            "Test Calendar",
            "#97c3c1",
            "A test calendar"
        );

        testee.createNewCalendar(user.username(), user.id(), newCalendar).block();

        List<CalendarURL> uris = testee.findUserCalendars(user.username(), user.id()).collectList().block();

        assertThat(uris).containsExactlyInAnyOrder(new CalendarURL(user.id(), user.id()), new CalendarURL(user.id(), new OpenPaaSId(newCalendar.id())));
    }

    @Test
    void findUserCalendarsShouldThrowWhenInvalidUserId() {
        OpenPaaSUser user = createOpenPaaSUser();

        assertThatThrownBy(() -> testee.findUserCalendars(user.username(), new OpenPaaSId("invalid")).collectList().block())
            .isInstanceOf(DavClientException.class);
    }

    @Test
    void findUserCalendarEventIdsShouldReturnEmptyWhenNoEvents() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL calendarURL = CalendarURL.from(user.id());

        // Trigger calendar directory activation
        testee.export(calendarURL, MailboxSessionUtil.create(user.username())).block();

        List<String> eventIds = testee.findUserCalendarEventIds(user.username(), calendarURL).collectList().block();

        assertThat(eventIds).isEmpty();
    }

    @Test
    void findUserCalendarEventIdsShouldReturnEventIds() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL calendarURL = CalendarURL.from(user.id());
        String uid = UUID.randomUUID().toString();
        String ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Test Event
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid);

        String uid2 = UUID.randomUUID().toString();
        String ics2 = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250102T100000Z
            DTSTART:20250103T120000Z
            DTEND:20250103T130000Z
            SUMMARY:Test Event 2
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid2);

        // Trigger calendar directory activation
        testee.export(calendarURL, MailboxSessionUtil.create(user.username())).block();

        testee.importCalendar(calendarURL, uid, user.username(), ics.getBytes(StandardCharsets.UTF_8)).block();
        testee.importCalendar(calendarURL, uid2, user.username(), ics2.getBytes(StandardCharsets.UTF_8)).block();
        List<String> eventIds = testee.findUserCalendarEventIds(user.username(), calendarURL).collectList().block();

        assertThat(eventIds).containsExactlyInAnyOrder(uid, uid2);
    }

    @Test
    void findUserCalendarEventIdsShouldThrowOnInvalidCalendar() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL invalidCalendarURL = new CalendarURL(user.id(), new OpenPaaSId("invalid"));

        assertThatThrownBy(() -> testee.findUserCalendarEventIds(user.username(), invalidCalendarURL).collectList().block())
            .isInstanceOf(DavClientException.class);
    }

    @Test
    void deleteCalendarEventShouldSucceed() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL calendarURL = CalendarURL.from(user.id());

        String uid = UUID.randomUUID().toString();
        String ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Test Event
            RRULE:FREQ=DAILY;COUNT=3
            CLASS:PUBLIC
            ORGANIZER;CN=john doe:mailto:%s
            ATTENDEE;PARTSTAT=accepted;RSVP=false;ROLE=chair;CUTYPE=individual:mailto:%s
            DESCRIPTION:This is a test event
            LOCATION:office
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid, user.username().asString(), user.username().asString(), user.username().asString());

        // To trigger calendar directory activation
        testee.export(calendarURL, MailboxSessionUtil.create(user.username())).block();

        testee.importCalendar(calendarURL, uid, user.username(), ics.getBytes(StandardCharsets.UTF_8)).block();

        testee.deleteCalendarEvent(user.username(), calendarURL, uid).block();

        String exportedCalendarString = testee.export(calendarURL, MailboxSessionUtil.create(user.username()))
            .map(bytes -> StringUtils.trim(new String(bytes, StandardCharsets.UTF_8)))
            .block();
        Calendar exportedCalendar = CalendarUtil.parseIcs(exportedCalendarString.getBytes(StandardCharsets.UTF_8));

        assertThat(exportedCalendar.getComponent(Component.VEVENT)).isEmpty();
    }

    @Test
    void deleteCalendarEventShouldNotThrowWhenCalendarEventNotFound() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL calendarURL = CalendarURL.from(user.id());

        // To trigger calendar directory activation
        testee.export(calendarURL, MailboxSessionUtil.create(user.username())).block();

        // Should not throw an exception even if the calendar does not exist
        testee.deleteCalendarEvent(user.username(), calendarURL, UUID.randomUUID().toString()).block();
    }

    @Test
    void deleteCalendarEventShouldNotDeleteWrongCalendarsEvent() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL calendarURL = CalendarURL.from(user.id());

        String uid = UUID.randomUUID().toString();
        String ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Test Event
            RRULE:FREQ=DAILY;COUNT=3
            CLASS:PUBLIC
            ORGANIZER;CN=john doe:mailto:%s
            ATTENDEE;PARTSTAT=accepted;RSVP=false;ROLE=chair;CUTYPE=individual:mailto:%s
            DESCRIPTION:This is a test event
            LOCATION:office
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid, user.username().asString(), user.username().asString(), user.username().asString());

        // To trigger calendar directory activation
        testee.export(calendarURL, MailboxSessionUtil.create(user.username())).block();

        testee.importCalendar(calendarURL, uid, user.username(), ics.getBytes(StandardCharsets.UTF_8)).block();

        testee.deleteCalendarEvent(user.username(), calendarURL, "other-uid").block();

        String exportedCalendarString = testee.export(calendarURL, MailboxSessionUtil.create(user.username()))
            .map(bytes -> StringUtils.trim(new String(bytes, StandardCharsets.UTF_8)))
            .block();
        Calendar exportedCalendar = CalendarUtil.parseIcs(exportedCalendarString.getBytes(StandardCharsets.UTF_8));

        Calendar calendar = CalendarUtil.parseIcs(ics.getBytes(StandardCharsets.UTF_8));
        VEvent expected = (VEvent) calendar.getComponent(Component.VEVENT).get();

        assertThat((VEvent) exportedCalendar.getComponent(Component.VEVENT).get()).isEqualTo(expected);
    }

    @Test
    void deleteCalendarShouldSucceed() {
        OpenPaaSUser user = createOpenPaaSUser();

        String newCalendarId = UUID.randomUUID().toString();
        CalendarURL newCalendarURL = new CalendarURL(user.id(), new OpenPaaSId(newCalendarId));
        CalDavClient.NewCalendar newCalendar = new CalDavClient.NewCalendar(
            newCalendarId,
            "Test Calendar",
            "#97c3c1",
            "A test calendar"
        );

        testee.createNewCalendar(user.username(), user.id(), newCalendar).block();

        testee.deleteCalendar(user.username(), newCalendarURL).block();

        List<CalendarURL> uris = testee.findUserCalendars(user.username(), user.id()).collectList().block();

        assertThat(uris).doesNotContain(newCalendarURL);
    }

    @Test
    void deleteCalendarShouldThrowWhenCalendarNotFound() {
        OpenPaaSUser user = createOpenPaaSUser();

        String newCalendarId = UUID.randomUUID().toString();
        CalendarURL newCalendarURL = new CalendarURL(user.id(), new OpenPaaSId(newCalendarId));

        // To trigger calendar directory activation
        testee.export(CalendarURL.from(user.id()), MailboxSessionUtil.create(user.username())).block();

        assertThatThrownBy(() -> testee.deleteCalendar(user.username(), newCalendarURL).block())
            .isInstanceOf(DavClientException.class);
    }

    @Test
    void deleteCalendarShouldNotDeleteWrongCalendar() {
        OpenPaaSUser user = createOpenPaaSUser();

        String newCalendarId = UUID.randomUUID().toString();
        CalendarURL newCalendarURL = new CalendarURL(user.id(), new OpenPaaSId(newCalendarId));
        CalDavClient.NewCalendar newCalendar = new CalDavClient.NewCalendar(
            newCalendarId,
            "Test Calendar",
            "#97c3c1",
            "A test calendar"
        );

        String anotherCalendarId = UUID.randomUUID().toString();
        CalendarURL anotherCalendarURL = new CalendarURL(user.id(), new OpenPaaSId(anotherCalendarId));
        CalDavClient.NewCalendar anotherCalendar = new CalDavClient.NewCalendar(
            anotherCalendarId,
            "Another Calendar",
            "#ff0000",
            "Another test calendar"
        );

        testee.createNewCalendar(user.username(), user.id(), newCalendar).block();
        testee.createNewCalendar(user.username(), user.id(), anotherCalendar).block();

        testee.deleteCalendar(user.username(), newCalendarURL).block();

        List<CalendarURL> uris = testee.findUserCalendars(user.username(), user.id()).collectList().block();

        assertThat(uris).containsExactlyInAnyOrder(CalendarURL.from(user.id()), anotherCalendarURL);
    }

    @Test
    void calendarReportByUidShouldReturnExpectedJsonNode() throws JsonProcessingException {
        OpenPaaSUser user = createOpenPaaSUser();
        String uid = UUID.randomUUID().toString();
        String ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Test Event
            RRULE:FREQ=DAILY;COUNT=3
            CLASS:PUBLIC
            ORGANIZER;CN=john doe:mailto:%s
            ATTENDEE;PARTSTAT=accepted;RSVP=false;ROLE=chair;CUTYPE=individual:mailto:%s
            DESCRIPTION:This is a test event
            LOCATION:office
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid, user.username().asString(), user.username().asString());

        davTestHelper.upsertCalendar(user, ics, uid);

        CalendarReportJsonResponse reportResponse = testee.calendarReportByUid(user.username(), user.id(), uid).block();

        assertThat(reportResponse).isNotNull();
        VCalendarDto vCalendarDto = VCalendarDto.from(reportResponse);

        assertThatJson(new ObjectMapper().writeValueAsString(vCalendarDto.value()))
            .isEqualTo("""
                [
                  "vcalendar",
                  [
                    ["version", {}, "text", "2.0"],
                    ["prodid", {}, "text", "-//Sabre//Sabre VObject 4.5.7//EN"]
                  ],
                  [
                    [
                      "vevent",
                      [
                        ["uid", {}, "text", "%s"],
                        ["transp", {}, "text", "OPAQUE"],
                        ["dtstamp", {}, "date-time", "2025-01-01T10:00:00Z"],
                        ["dtstart", {}, "date-time", "2025-01-02T12:00:00Z"],
                        ["dtend", {}, "date-time", "2025-01-02T13:00:00Z"],
                        ["summary", {}, "text", "Test Event"],
                        ["rrule", {}, "recur", {"freq": "DAILY", "count": 3}],
                        ["class", {}, "text", "PUBLIC"],
                        ["organizer", {"cn": "john doe"}, "cal-address", "mailto:%s"],
                        ["attendee", {
                          "partstat": "accepted",
                          "rsvp": "false",
                          "role": "chair",
                          "cutype": "individual"
                        }, "cal-address", "mailto:%s"],
                        ["description", {}, "text", "This is a test event"],
                        ["location", {}, "text", "office"]
                      ],
                      []
                    ]
                  ]
                ]
                """.formatted(uid, user.username().asString(), user.username().asString()));
    }

    @Test
    void calendarReportByUidShouldReturnEmptyWhenEventUidNotFound() {
        OpenPaaSUser user = createOpenPaaSUser();
        String nonExistentUid = UUID.randomUUID().toString();

        assertThat(testee.calendarReportByUid(user.username(), user.id(), nonExistentUid).blockOptional())
            .isEmpty();
    }

    @Test
    void calendarReportByUidShouldReturnEmptyWhenCalendarIdNotFound() {
        OpenPaaSUser user = createOpenPaaSUser();

        String uid = UUID.randomUUID().toString();
        String ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Test Event
            RRULE:FREQ=DAILY;COUNT=3
            CLASS:PUBLIC
            ORGANIZER;CN=john doe:mailto:%s
            ATTENDEE;PARTSTAT=accepted;RSVP=false;ROLE=chair;CUTYPE=individual:mailto:%s
            DESCRIPTION:This is a test event
            LOCATION:office
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid, user.username().asString(), user.username().asString());

        davTestHelper.upsertCalendar(user, ics, uid);

        assertThat(testee.calendarReportByUid(user.username(), createOpenPaaSUser().id(), uid).blockOptional())
            .isEmpty();
    }

    @Test
    void calendarQueryReportXmlShouldReturnCalendarObjectsFilteredByDtStart() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL calendarURL = CalendarURL.from(user.id());

        String uid = UUID.randomUUID().toString();
        String ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            DTSTART:20250110T120000Z
            DTEND:20250110T130000Z
            SUMMARY:Filtered Event
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid);

        String uid2 = UUID.randomUUID().toString();
        String ics2 = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            DTSTART:20250201T120000Z
            DTEND:20250201T130000Z
            SUMMARY:Non Filtered Event
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid2);

        davTestHelper.upsertCalendar(user, ics, uid);
        davTestHelper.upsertCalendar(user, ics2, uid2);

        CalendarQuery query = CalendarQuery.ofFilters(TimeRangePropFilter.dtStartBefore(Instant.parse("2025-01-15T00:00:00Z")));
        CalendarReportXmlResponse response = testee.calendarQueryReportXml(user.username(), calendarURL, query).block();

        assertThat(response).isNotNull();

        List<CalendarObject> objects = response.extractCalendarObjects();

        // Assert that at least one CalendarObject matches the expected href and calendarData
        // Assert that no CalendarObject contains data from the second ICS (uid2)
        assertThat(objects)
            .anySatisfy(obj -> {
                assertThat(obj.href().toString())
                    .endsWith("/calendars/" + user.id().value() + "/" + user.id().value() + "/" + uid + ".ics");
                assertThat(obj.calendarData())
                    .startsWith("BEGIN:VCALENDAR")
                    .contains(uid);
            })
            .allSatisfy(obj -> assertThat(obj.calendarData()).doesNotContain(uid2));
    }

    @Test
    void calendarQueryReportXmlShouldReturnCalendarObjectsFilteredByAttendeeDeclined() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL calendarURL = CalendarURL.from(user.id());

        String uidAccepted = UUID.randomUUID().toString();
        String icsAccepted = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            DTSTART:20250110T120000Z
            DTEND:20250110T130000Z
            SUMMARY:Accepted Event
            ATTENDEE;PARTSTAT=ACCEPTED:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(uidAccepted, user.username().asString());

        String uidDeclined = UUID.randomUUID().toString();
        String icsDeclined = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            DTSTART:20250111T120000Z
            DTEND:20250111T130000Z
            SUMMARY:Declined Event
            ATTENDEE;PARTSTAT=DECLINED:mailto:%s
            ATTENDEE;PARTSTAT=ACCEPTED:mailto:someone-else@example.com
            END:VEVENT
            END:VCALENDAR
            """.formatted(uidDeclined, user.username().asString());

        davTestHelper.upsertCalendar(user, icsAccepted, uidAccepted);
        davTestHelper.upsertCalendar(user, icsDeclined, uidDeclined);

        CalendarQuery query = CalendarQuery.ofFilters(CalendarQuery.AttendeePropFilter.declined(user.username()));

        CalendarReportXmlResponse response = testee.calendarQueryReportXml(user.username(), calendarURL, query).block();

        assertThat(response).isNotNull();

        List<CalendarObject> objects = response.extractCalendarObjects();

        // Must contain declined event
        // Must not contain accepted event
        assertThat(objects)
            .anySatisfy(obj -> {
                assertThat(obj.href().toString())
                    .endsWith("/calendars/" + user.id().value() + "/" + user.id().value() + "/" + uidDeclined + ".ics");
                assertThat(obj.calendarData())
                    .startsWith("BEGIN:VCALENDAR")
                    .contains(uidDeclined);
            })
            .allSatisfy(obj -> assertThat(obj.calendarData()).doesNotContain(uidAccepted));
    }

    @Test
    void calendarQueryReportXmlShouldReturnCalendarObjectsFilteredByDtStamp() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL calendarURL = CalendarURL.from(user.id());

        String uid1 = UUID.randomUUID().toString();
        String ics1 = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            DTSTART:20250110T120000Z
            DTEND:20250110T130000Z
            SUMMARY:Filtered Event 1
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid1);

        String uid2 = UUID.randomUUID().toString();
        String ics2 = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250105T100000Z
            DTSTART:20250111T120000Z
            DTEND:20250111T130000Z
            SUMMARY:Filtered Event 2
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid2);

        String uid3 = UUID.randomUUID().toString();
        String ics3 = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250201T100000Z
            DTSTART:20250202T120000Z
            DTEND:20250202T130000Z
            SUMMARY:Non Filtered Event
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid3);

        davTestHelper.upsertCalendar(user, ics1, uid1);
        davTestHelper.upsertCalendar(user, ics2, uid2);
        davTestHelper.upsertCalendar(user, ics3, uid3);

        CalendarQuery query = CalendarQuery.ofFilters(TimeRangePropFilter.dtStampBefore(Instant.parse("2025-01-10T00:00:00Z")));

        CalendarReportXmlResponse response = testee.calendarQueryReportXml(user.username(), calendarURL, query).block();
        assertThat(response).isNotNull();

        List<CalendarObject> objects = response.extractCalendarObjects();

        // Must contain the two filtered events
        assertThat(objects)
            .anySatisfy(obj -> assertThat(obj.calendarData()).contains(uid1))
            .anySatisfy(obj -> assertThat(obj.calendarData()).contains(uid2))
            .allSatisfy(obj -> assertThat(obj.calendarData()).doesNotContain(uid3));
    }

    @Test
    void calendarQueryReportXmlShouldReturnCalendarObjectsFilteredByLastModified() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL calendarURL = CalendarURL.from(user.id());

        String uidOld = UUID.randomUUID().toString();
        String icsOld = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            LAST-MODIFIED:20250101T100000Z
            DTSTART:20250110T120000Z
            DTEND:20250110T130000Z
            SUMMARY:Old Event
            END:VEVENT
            END:VCALENDAR
            """.formatted(uidOld);

        // Add a second ICS that does NOT match the last-modified filter
        String uidNew = UUID.randomUUID().toString();
        String icsNew = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250201T100000Z
            LAST-MODIFIED:20250201T100000Z
            DTSTART:20250202T120000Z
            DTEND:20250202T130000Z
            SUMMARY:New Event
            END:VEVENT
            END:VCALENDAR
            """.formatted(uidNew);

        davTestHelper.upsertCalendar(user, icsOld, uidOld);
        davTestHelper.upsertCalendar(user, icsNew, uidNew);

        Instant beforeInstant = Instant.parse("2025-01-10T00:00:00Z");
        CalendarQuery query = CalendarQuery.ofFilters(TimeRangePropFilter.lastModifiedBefore(beforeInstant));

        CalendarReportXmlResponse response = testee.calendarQueryReportXml(user.username(), calendarURL, query).block();

        assertThat(response).isNotNull();

        List<CalendarObject> objects = response.extractCalendarObjects();

        assertThat(objects)
            .anySatisfy(obj -> {
                assertThat(obj.href().toString())
                    .endsWith("/calendars/" + user.id().value() + "/" + user.id().value() + "/" + uidOld + ".ics");

                assertThat(obj.calendarData().trim())
                    .isEqualToNormalizingNewlines("""
                        BEGIN:VCALENDAR
                        VERSION:2.0
                        PRODID:-//Sabre//Sabre VObject 4.5.7//EN
                        BEGIN:VEVENT
                        UID:%s
                        DTSTAMP:20250101T100000Z
                        LAST-MODIFIED:20250101T100000Z
                        DTSTART:20250110T120000Z
                        DTEND:20250110T130000Z
                        SUMMARY:Old Event
                        END:VEVENT
                        END:VCALENDAR
                        """.formatted(uidOld).trim());
            })
            .allSatisfy(obj -> assertThat(obj.calendarData()).doesNotContain(uidNew));
    }

    @Test
    void updateCalendarAclShouldSucceed() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL calendarURL = CalendarURL.from(user.id());

        testee.updateCalendarAcl(user.username(), calendarURL, CalDavClient.PublicRight.READ).block();

        String response = davTestHelper.getCalendarMetadata(user).block();

        assertThatJson(response)
            .inPath("$.acl")
            .isArray()
            .contains("""
                {
                  "privilege": "{DAV:}read",
                  "principal": "{DAV:}authenticated",
                  "protected": true
                }
                """);
    }

    @Test
    void shouldRetrieveSyncTokenWhenOwnerAccessesCalendar() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL calendarURL = CalendarURL.from(user.id());

        SyncToken syncToken = testee.retrieveSyncToken(user.username(), calendarURL).block();
        assertThat(syncToken).isEqualTo(new SyncToken("http://sabre.io/ns/sync/1"));
    }

    @Test
    void shouldRetrieveSyncTokenWhenPublicCalendarIsShared() {
        OpenPaaSUser owner = createOpenPaaSUser();
        OpenPaaSUser otherUser = createOpenPaaSUser();

        CalDavClient.NewCalendar publicCalendar = new CalDavClient.NewCalendar(UUID.randomUUID().toString(),
            "Public Calendar", "#123456", "Public calendar for testing");

        testee.createNewCalendar(owner.username(), owner.id(), publicCalendar).block();

        // Owner sets public ACL (READ)
        CalendarURL calendarURL = new CalendarURL(owner.id(), new OpenPaaSId(publicCalendar.id()));
        testee.updateCalendarAcl(owner.username(), calendarURL, CalDavClient.PublicRight.READ).block();

        // Other user should be allowed to retrieve sync token due to public rights
        SyncToken syncToken = testee.retrieveSyncToken(otherUser.username(), calendarURL).block();

        assertThat(syncToken).isNotNull();
    }


    @Test
    void shouldRetrieveSyncTokenWhenDelegateHasRights() {
        // Owner & delegate
        OpenPaaSUser owner = createOpenPaaSUser();
        OpenPaaSUser delegate = createOpenPaaSUser();

        NewCalendar newCalendar = new NewCalendar(UUID.randomUUID().toString(),
            "Delegated Calendar", "#00AA00", "A calendar shared via delegation");

        testee.createNewCalendar(owner.username(), owner.id(), newCalendar).block();

        OpenPaaSId domainId = new MongoDBOpenPaaSDomainDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB())
            .retrieve(owner.username().getDomainPart().get())
            .map(OpenPaaSDomain::id).block();

        CalendarURL calendarURL = new CalendarURL(owner.id(), new OpenPaaSId(newCalendar.id()));
        testee.patchReadWriteDelegations(domainId, calendarURL, List.of(delegate.username()), List.of()).block();

        List<CalendarURL> delegateCalendars = testee.findUserCalendars(delegate.username(), delegate.id()).collectList().block();

        // Find the delegated calendar (not equal to ownerCalendarUrl)
        CalendarURL delegatedCalendar = delegateCalendars.stream()
            .filter(url -> !url.equals(CalendarURL.from(delegate.id()))) // exclude personal calendar
            .findFirst()
            .orElseThrow();

        SyncToken token = testee.retrieveSyncToken(delegate.username(), delegatedCalendar).block();
        assertThat(token).isNotNull();
    }

    @Test
    void retrieveSyncTokenShouldReturnEmptyWhenUserHasNoRights() {
        OpenPaaSUser owner = createOpenPaaSUser();
        OpenPaaSUser other = createOpenPaaSUser();
        NewCalendar newCalendar = new NewCalendar(UUID.randomUUID().toString(),
            "My Calendar", "#00ff00", "Test");

        testee.createNewCalendar(owner.username(), owner.id(), newCalendar).block();

        CalendarURL calendarURL = new CalendarURL(owner.id(), new OpenPaaSId(newCalendar.id()));

        assertThat(testee.retrieveSyncToken(other.username(), calendarURL).blockOptional()).isEmpty();
    }

    @Test
    void retrieveSyncTokenShouldThrowWhenCalendarNotFound() {
        OpenPaaSUser user = createOpenPaaSUser();
        CalendarURL notFoundCalendarURL = new CalendarURL(user.id(), new OpenPaaSId(UUID.randomUUID().toString()));

        assertThatThrownBy(() -> testee.retrieveSyncToken(user.username(), notFoundCalendarURL).block())
            .isInstanceOf(CalendarNotFoundException.class);

    }
}
