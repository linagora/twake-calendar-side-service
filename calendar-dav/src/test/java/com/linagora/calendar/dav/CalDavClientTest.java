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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.MailboxSessionUtil;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;

public class CalDavClientTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private CalDavClient testee;

    @BeforeEach
    void setupEach() throws Exception {
        testee = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration());
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
}
