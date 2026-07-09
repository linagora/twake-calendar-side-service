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
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.restapi.routes.BookingLinkEventIcsBuilder.BuildResult;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest.BookingAttendee;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.immutable.ImmutableMethod;
import net.fortuna.ical4j.util.UidGenerator;

public class BookingLinkEventIcsBuilderTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2036-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final URL VISIO_URL = Throwing.supplier(() -> URI.create("https://jitsi.example.com").toURL()).get();
    private static final UidGenerator FIXED_UID_GENERATOR = () -> new Uid("event-123");
    private static final BookingAttendee OWNER = BookingAttendee.from("Alice Owner", "owner@example.com");
    private static final BookingLinkPublicId BOOKING_LINK_PUBLIC_ID = new BookingLinkPublicId(UUID.fromString("a1b2c3d4-e5f6-4a5b-8c7d-0e1f2a3b4c5d"));

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
            SUMMARY:30-min intro call
            DTSTAMP:20360101T000000Z
            DTSTART:20360126T093000Z
            DURATION:PT30M
            ORGANIZER;CN=Alice Owner:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;CN=Alice Owner:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED;CN=BOB:mailto:creator@example.com
            ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED;CN=Nguyen Van A:mailto:vana@example.com
            DESCRIPTION:Please call via Zoom.\\nVisio: https://jitsi.example.com
            CLASS:PUBLIC
            X-PUBLICLY-CREATED;VALUE=BOOLEAN:TRUE
            X-PUBLICLY-CREATOR:creator@example.com
            X-OPENPAAS-BOOKING-LINK:a1b2c3d4-e5f6-4a5b-8c7d-0e1f2a3b4c5d
            X-OPENPAAS-VIDEOCONFERENCE;VALUE=URI:https://jitsi.example.com
            END:VEVENT
            END:VCALENDAR
            """;

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
            SUMMARY:eventTitle
            DTSTAMP:20360101T000000Z
            DTSTART:20360126T093000Z
            DURATION:PT30M
            ORGANIZER;CN=Alice Owner:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;CN=Alice Owner:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED;CN=BOB:mailto:creator@example.com
            ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED;CN=Nguyen Van A:mailto:vana@example.com
            CLASS:PUBLIC
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
            .contains("DESCRIPTION:Visio: https://jitsi.example.com");
        assertThat(ics)
            .contains("X-OPENPAAS-VIDEOCONFERENCE;VALUE=URI:https://jitsi.example.com");
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
            SUMMARY:eventTitle
            DTSTAMP:20360101T000000Z
            DTSTART:20360126T093000Z
            DURATION:PT30M
            ORGANIZER:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED:mailto:creator@example.com
            CLASS:PUBLIC
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
    void icsBytesShouldNotCarryAMethodByDefault() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BuildResult result = testee.build(bookingRequest(), OWNER, Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID);

        assertThat(new String(result.icsBytes(), StandardCharsets.UTF_8))
            .doesNotContain("METHOD:");
    }

    @Test
    void icsBytesShouldCarrySuppliedMethod() {
        BookingLinkEventIcsBuilder testee = new BookingLinkEventIcsBuilder(FIXED_CLOCK, () -> VISIO_URL, FIXED_UID_GENERATOR);

        BuildResult result = testee.build(bookingRequest(), OWNER, Duration.ofMinutes(30), BOOKING_LINK_PUBLIC_ID);

        assertThat(new String(result.icsBytes(ImmutableMethod.REQUEST), StandardCharsets.UTF_8))
            .contains("METHOD:REQUEST");
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
