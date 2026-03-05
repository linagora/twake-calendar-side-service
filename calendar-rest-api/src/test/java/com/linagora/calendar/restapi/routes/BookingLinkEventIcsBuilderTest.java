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

import org.junit.jupiter.api.Test;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.restapi.routes.BookingLinkEventIcsBuilder.BuildResult;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest.BookingAttendee;

import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.util.UidGenerator;

public class BookingLinkEventIcsBuilderTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2036-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final URL VISIO_URL = Throwing.supplier(() -> URI.create("https://jitsi.example.com").toURL()).get();
    private static final UidGenerator FIXED_UID_GENERATOR = () -> new Uid("event-123");

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

        BuildResult result = testee.build(request, Duration.ofMinutes(30));
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
            ORGANIZER;CN=BOB;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION:mailto:creator@example.com
            ATTENDEE;CN=BOB;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION:mailto:creator@example.com
            ATTENDEE;CN=Nguyen Van A;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION:mailto:vana@example.com
            DESCRIPTION:Please call via Zoom.
            CLASS:PUBLIC
            X-PUBLICLY-CREATED:true
            X-PUBLICLY-CREATOR:creator@example.com
            X-OPENPAAS-VIDEOCONFERENCE:https://jitsi.example.com
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

        BuildResult result = testee.build(request, Duration.ofMinutes(30));
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
            ORGANIZER;CN=BOB;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION:mailto:creator@example.com
            ATTENDEE;CN=BOB;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION:mailto:creator@example.com
            ATTENDEE;CN=Nguyen Van A;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION:mailto:vana@example.com
            CLASS:PUBLIC
            X-PUBLICLY-CREATED:true
            X-PUBLICLY-CREATOR:creator@example.com
            END:VEVENT
            END:VCALENDAR
            """;

        assertThat(result.eventIdAsString())
            .isEqualTo("event-123");
        assertThat(ics)
            .isEqualToNormalizingNewlines(expected);
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

        BuildResult result = testee.build(request, Duration.ofMinutes(30));
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
            ORGANIZER;CN;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION:mailto:creator@example.com
            ATTENDEE;CN;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION:mailto:creator@example.com
            CLASS:PUBLIC
            X-PUBLICLY-CREATED:true
            X-PUBLICLY-CREATOR:creator@example.com
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

        String ics = new String(testee.build(request, Duration.ofMinutes(30)).icsBytes(), StandardCharsets.UTF_8);

        assertThat(ics)
            .contains("ORGANIZER;CN=BOB;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION:mailto:creator@example.com");
    }

}
