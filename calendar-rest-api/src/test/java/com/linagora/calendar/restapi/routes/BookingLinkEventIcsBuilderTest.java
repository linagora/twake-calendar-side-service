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

package com.linagora.calendar.restapi.routes;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.restapi.routes.BookingLinkEventIcsBuilder.BookingEventOptions;
import com.linagora.calendar.restapi.routes.BookingLinkEventIcsBuilder.BuildResult;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest.BookingAttendee;
import com.linagora.calendar.storage.booking.BookingLinkAlarm;
import com.linagora.calendar.storage.booking.BookingLinkAlarmAction;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;
import com.linagora.calendar.storage.booking.EventTransparency;
import com.linagora.calendar.storage.booking.EventVisibility;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.util.UidGenerator;

public class BookingLinkEventIcsBuilderTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2036-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final URL VISIO_URL = Throwing.supplier(() -> URI.create("https://jitsi.example.com").toURL()).get();
    private static final UidGenerator FIXED_UID_GENERATOR = () -> new Uid("event-123");
    private static final BookingAttendee OWNER = BookingAttendee.from("Alice Owner", "owner@example.com");
    private static final BookingLinkPublicId BOOKING_LINK_PUBLIC_ID = new BookingLinkPublicId(UUID.fromString("a1b2c3d4-e5f6-4a5b-8c7d-0e1f2a3b4c5d"));
    private static final String FOOTER_SEPARATOR = EventParseUtils.EVENT_FOOTER_SEPARATOR;
    /** As written in the ICS: the DESCRIPTION value carries escaped newlines. */
    private static final String VISIO_FOOTER = FOOTER_SEPARATOR + "\\nVisio: " + VISIO_URL
        + "\\n\\nPlease do not edit this section.\\n" + FOOTER_SEPARATOR;

    @Test
    void buildShouldIncludeRequiredPublicBookingProperties() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BookingRequest request = new BookingRequest(
            Instant.parse("2036-01-26T09:30:00Z"),
            BookingAttendee.from("BOB", "creator@example.com"),
            List.of(BookingAttendee.from("Nguyen Van A", "vana@example.com")),
            "30-min intro call",
            true,
            "Please call via Zoom.");

        BuildResult result = testee.build(request, OWNER, Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID);
        String ics = new String(result.icsBytes(), StandardCharsets.UTF_8);

        String expected = """
            BEGIN:VCALENDAR
            CALSCALE:GREGORIAN
            VERSION:2.0
            PRODID:-//Twake Calendar//Public Booking//EN
            BEGIN:VEVENT
            UID:event-123
            TRANSP:OPAQUE
            CLASS:PUBLIC
            SUMMARY:30-min intro call
            DTSTAMP:20360101T000000Z
            SEQUENCE:0
            DTSTART:20360126T093000Z
            DURATION:PT30M
            ORGANIZER;CN=Alice Owner:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;CN=Alice Owner:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED;CN=BOB:mailto:creator@example.com
            ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED;CN=Nguyen Van A:mailto:vana@example.com
            DESCRIPTION:Please call via Zoom.\\n\\n%s
            X-PUBLICLY-CREATED;VALUE=BOOLEAN:TRUE
            X-PUBLICLY-CREATOR:creator@example.com
            X-OPENPAAS-BOOKING-LINK:a1b2c3d4-e5f6-4a5b-8c7d-0e1f2a3b4c5d
            X-OPENPAAS-VIDEOCONFERENCE;VALUE=URI:https://jitsi.example.com
            END:VEVENT
            END:VCALENDAR
            """.formatted(VISIO_FOOTER);

        assertThat(result.eventIdAsString())
            .isEqualTo("event-123");
        assertThat(ics)
            .isEqualToNormalizingNewlines(expected);
    }

    @Test
    void buildShouldNotIncludeVisioLinkWhenVisioLinkIsFalse() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BookingRequest request = new BookingRequest(
            Instant.parse("2036-01-26T09:30:00Z"),
            BookingAttendee.from("BOB", "creator@example.com"),
            List.of(BookingAttendee.from("Nguyen Van A", "vana@example.com")),
            "eventTitle",
            false,
            "");

        BuildResult result = testee.build(request, OWNER, Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID);
        String ics = new String(result.icsBytes(), StandardCharsets.UTF_8);

        String expected = """
            BEGIN:VCALENDAR
            CALSCALE:GREGORIAN
            VERSION:2.0
            PRODID:-//Twake Calendar//Public Booking//EN
            BEGIN:VEVENT
            UID:event-123
            TRANSP:OPAQUE
            CLASS:PUBLIC
            SUMMARY:eventTitle
            DTSTAMP:20360101T000000Z
            SEQUENCE:0
            DTSTART:20360126T093000Z
            DURATION:PT30M
            ORGANIZER;CN=Alice Owner:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;CN=Alice Owner:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED;CN=BOB:mailto:creator@example.com
            ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED;CN=Nguyen Van A:mailto:vana@example.com
            X-PUBLICLY-CREATED;VALUE=BOOLEAN:TRUE
            X-PUBLICLY-CREATOR:creator@example.com
            X-OPENPAAS-BOOKING-LINK:a1b2c3d4-e5f6-4a5b-8c7d-0e1f2a3b4c5d
            END:VEVENT
            END:VCALENDAR
            """;

        assertThat(result.eventIdAsString())
            .isEqualTo("event-123");
        assertThat(ics)
            .isEqualToNormalizingNewlines(expected);
    }

    @Test
    void buildShouldAppendVisioLinkAsDescriptionWhenEnabledWithoutNotes() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BookingRequest request = new BookingRequest(
            Instant.parse("2036-01-26T09:30:00Z"),
            BookingAttendee.from("BOB", "creator@example.com"),
            List.of(),
            "eventTitle",
            true,
            null);

        String ics = new String(testee.build(request, OWNER, Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID).icsBytes(), StandardCharsets.UTF_8);

        assertThat(ics)
            .contains("DESCRIPTION:" + VISIO_FOOTER);
        assertThat(ics)
            .contains("X-OPENPAAS-VIDEOCONFERENCE;VALUE=URI:https://jitsi.example.com");
    }

    @Test
    void visioSectionShouldBeHiddenWhenReadingBackTheDescription() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BookingRequest request = new BookingRequest(
            Instant.parse("2036-01-26T09:30:00Z"),
            BookingAttendee.from("BOB", "creator@example.com"),
            List.of(),
            "eventTitle",
            true,
            "Please call via Zoom.");

        VEvent event = (VEvent) testee.build(request, OWNER, Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID)
            .calendar()
            .getComponent(Component.VEVENT)
            .get();

        assertThat(EventParseUtils.getDescription(event))
            .contains("Please call via Zoom.");
    }

    @Test
    void buildShouldHandleNullFields() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BookingRequest request = new BookingRequest(
            Instant.parse("2036-01-26T09:30:00Z"),
            BookingAttendee.from(null, "creator@example.com"),
            List.of(),
            "eventTitle",
            false,
            null);

        BuildResult result = testee.build(request, BookingAttendee.from(null, "owner@example.com"), Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID);
        String ics = new String(result.icsBytes(), StandardCharsets.UTF_8);

        String expected = """
            BEGIN:VCALENDAR
            CALSCALE:GREGORIAN
            VERSION:2.0
            PRODID:-//Twake Calendar//Public Booking//EN
            BEGIN:VEVENT
            UID:event-123
            TRANSP:OPAQUE
            CLASS:PUBLIC
            SUMMARY:eventTitle
            DTSTAMP:20360101T000000Z
            SEQUENCE:0
            DTSTART:20360126T093000Z
            DURATION:PT30M
            ORGANIZER:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED:mailto:creator@example.com
            X-PUBLICLY-CREATED;VALUE=BOOLEAN:TRUE
            X-PUBLICLY-CREATOR:creator@example.com
            X-OPENPAAS-BOOKING-LINK:a1b2c3d4-e5f6-4a5b-8c7d-0e1f2a3b4c5d
            END:VEVENT
            END:VCALENDAR
            """;

        assertThat(result.eventIdAsString())
            .isEqualTo("event-123");
        assertThat(ics)
            .isEqualToNormalizingNewlines(expected);
    }

    @Test
    void buildShouldSetOrganizerPartStatNeedsAction() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BookingRequest request = new BookingRequest(
            Instant.parse("2036-01-26T09:30:00Z"),
            BookingAttendee.from("BOB", "creator@example.com"),
            List.of(),
            "eventTitle",
            false,
            null);

        String ics = new String(testee.build(request, OWNER, Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID).icsBytes(), StandardCharsets.UTF_8);

        assertThat(ics)
            .contains("ORGANIZER;CN=Alice Owner:mailto:owner@example.com");
        assertThat(ics)
            .contains("ATTENDEE;RSVP=TRUE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;CN=Alice Owner:mailto:owner@example.com");
    }

    @Test
    void buildShouldReferenceTheBookingLink() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BookingRequest request = new BookingRequest(
            Instant.parse("2036-01-26T09:30:00Z"),
            BookingAttendee.from("BOB", "creator@example.com"),
            List.of(),
            "eventTitle",
            false,
            null);

        String ics = new String(testee.build(request, OWNER, Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID).icsBytes(), StandardCharsets.UTF_8);

        assertThat(ics)
            .contains("X-OPENPAAS-BOOKING-LINK:a1b2c3d4-e5f6-4a5b-8c7d-0e1f2a3b4c5d");
    }

    @Test
    void buildShouldSetOrganizerPartStatAcceptedWhenAutoAccept() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BookingRequest request = new BookingRequest(
            Instant.parse("2036-01-26T09:30:00Z"),
            BookingAttendee.from("BOB", "creator@example.com"),
            List.of(),
            "eventTitle",
            false,
            null);

        String ics = new String(testee.build(request, OWNER, Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID, true).icsBytes(), StandardCharsets.UTF_8);

        assertThat(ics)
            .contains("ORGANIZER;CN=Alice Owner:mailto:owner@example.com");
        assertThat(ics)
            .contains("ATTENDEE;RSVP=TRUE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED;CN=Alice Owner:mailto:owner@example.com");
    }

    @Test
    void buildShouldAddExtraAttendeesAsNeedsActionAttendees() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        List<BookingAttendee> extraAttendees = List.of(
            BookingAttendee.from("Presales Engineer", "presales@example.com"),
            BookingAttendee.from("Project Manager", "pm@example.com"));

        String ics = new String(testee.build(bookingRequest(), OWNER, extraAttendees, Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID, false)
            .icsBytes(), StandardCharsets.UTF_8);

        assertThat(ics)
            .contains("ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;CN=Presales Engineer:mailto:presales@example.com")
            .contains("ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;CN=Project Manager:mailto:pm@example.com");
    }

    @Test
    void buildShouldNotDuplicateExtraAttendeeAlreadyInvitedByTheBooker() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BookingRequest request = new BookingRequest(
            Instant.parse("2036-01-26T09:30:00Z"),
            BookingAttendee.from("BOB", "creator@example.com"),
            List.of(BookingAttendee.from("Presales Engineer", "presales@example.com")),
            "eventTitle",
            false,
            null);

        // Both the booker and an additional attendee collide with an extra attendee of the booking link.
        List<BookingAttendee> extraAttendees = List.of(
            BookingAttendee.from("Presales Engineer", "presales@example.com"),
            BookingAttendee.from("BOB", "creator@example.com"));

        String ics = new String(testee.build(request, OWNER, extraAttendees, Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID, false)
            .icsBytes(), StandardCharsets.UTF_8);

        assertThat(ics.lines().filter(line -> line.startsWith("ATTENDEE")))
            .describedAs("already invited attendees keep their single ATTENDEE line")
            .hasSize(3)
            .doesNotContain("ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;CN=Presales Engineer:mailto:presales@example.com");
    }

    @Test
    void buildShouldNotAddAnyAttendeeWhenNoExtraAttendee() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        String ics = new String(testee.build(bookingRequest(), OWNER, List.of(), Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID, false)
            .icsBytes(), StandardCharsets.UTF_8);

        assertThat(ics.lines().filter(line -> line.startsWith("ATTENDEE")))
            .hasSize(2);
    }

    @Test
    void icsBytesShouldNotCarryAMethodByDefault() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BuildResult result = testee.build(bookingRequest(), OWNER, Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID);

        assertThat(new String(result.icsBytes(), StandardCharsets.UTF_8))
            .doesNotContain("METHOD:");
    }

    @Test
    void buildShouldIncludeLocationWhenPresent() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BookingEventOptions options = new BookingEventOptions(Optional.of("Room 3"),
            Optional.empty(), Optional.empty(), List.of(), List.of());

        String ics = new String(testee.build(bookingRequest(), OWNER, List.of(), Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID, false, options)
            .icsBytes(), StandardCharsets.UTF_8);

        assertThat(ics)
            .contains("LOCATION:Room 3");
    }

    @Test
    void buildShouldUsePrivateClassWhenVisibilityPrivate() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BookingEventOptions options = new BookingEventOptions(Optional.empty(),
            Optional.of(EventVisibility.PRIVATE), Optional.empty(), List.of(), List.of());

        String ics = new String(testee.build(bookingRequest(), OWNER, List.of(), Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID, false, options)
            .icsBytes(), StandardCharsets.UTF_8);

        assertThat(ics)
            .contains("CLASS:PRIVATE")
            .doesNotContain("CLASS:PUBLIC");
    }

    @Test
    void buildShouldUseTransparentWhenTransparencyTransparent() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BookingEventOptions options = new BookingEventOptions(Optional.empty(),
            Optional.empty(), Optional.of(EventTransparency.TRANSPARENT), List.of(), List.of());

        String ics = new String(testee.build(bookingRequest(), OWNER, List.of(), Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID, false, options)
            .icsBytes(), StandardCharsets.UTF_8);

        assertThat(ics)
            .contains("TRANSP:TRANSPARENT")
            .doesNotContain("TRANSP:OPAQUE");
    }

    @Test
    void buildShouldAddResourcesAsResourceAttendees() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BookingEventOptions options = new BookingEventOptions(Optional.empty(), Optional.empty(), Optional.empty(),
            List.of(BookingAttendee.from("Projector", "projector-id@example.com")), List.of());

        String ics = new String(testee.build(bookingRequest(), OWNER, List.of(), Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID, false, options)
            .icsBytes(), StandardCharsets.UTF_8);

        assertThat(ics)
            .contains("ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;PARTSTAT=NEEDS-ACTION;CN=Projector:mailto:projector-id@example.com");
    }

    @Test
    void buildShouldAddEmailAlarmAddressedToEveryAttendee() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BookingRequest request = new BookingRequest(
            Instant.parse("2036-01-26T09:30:00Z"),
            BookingAttendee.from("BOB", "creator@example.com"),
            List.of(BookingAttendee.from("Nguyen Van A", "vana@example.com")),
            "eventTitle",
            false,
            null);
        BookingEventOptions options = new BookingEventOptions(Optional.empty(), Optional.empty(), Optional.empty(),
            List.of(), List.of(new BookingLinkAlarm("-PT10M", BookingLinkAlarmAction.EMAIL), new BookingLinkAlarm("-P1W", BookingLinkAlarmAction.EMAIL)));

        String ics = new String(testee.build(request, OWNER, List.of(), Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID, false, options)
            .icsBytes(), StandardCharsets.UTF_8);

        assertThat(ics)
            .containsIgnoringNewLines("""
                BEGIN:VALARM
                ACTION:EMAIL
                TRIGGER:-PT10M
                ATTENDEE:mailto:owner@example.com
                ATTENDEE:mailto:creator@example.com
                ATTENDEE:mailto:vana@example.com
                END:VALARM
                BEGIN:VALARM
                ACTION:EMAIL
                TRIGGER:-P1W
                ATTENDEE:mailto:owner@example.com
                ATTENDEE:mailto:creator@example.com
                ATTENDEE:mailto:vana@example.com
                END:VALARM
                """);
    }

    @Test
    void buildWithoutOptionsShouldDefaultToOpaquePublicWithoutAlarm() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        String ics = new String(testee.build(bookingRequest(), OWNER, List.of(), Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID, false, BookingEventOptions.none())
            .icsBytes(), StandardCharsets.UTF_8);

        assertThat(ics)
            .contains("TRANSP:OPAQUE")
            .contains("CLASS:PUBLIC")
            .doesNotContain("BEGIN:VALARM")
            .doesNotContain("LOCATION:");
    }

    private BookingRequest bookingRequest() {
        return new BookingRequest(
            Instant.parse("2036-01-26T09:30:00Z"),
            BookingAttendee.from("BOB", "creator@example.com"),
            List.of(),
            "eventTitle",
            false,
            null);
    }
}
