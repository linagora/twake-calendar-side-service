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
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.net.URI;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;

class BookedEventCancelledTest {
    private static final OpenPaaSUser ORGANIZER = new OpenPaaSUser(Username.of("owner@example.com"), new OpenPaaSId("owner-id"), "Alice", "Owner");

    @Test
    void fromShouldResolveCancelledByFromPubliclyCreatorAttendee() {
        BookedEventCancelled result = BookedEventCancelled.from(ORGANIZER, CalendarUtil.parseIcs("""
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-123
            SUMMARY:30-min intro call
            DTSTART:20360126T093000Z
            DURATION:PT30M
            ORGANIZER;CN=Alice Owner:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;CN=Alice Owner:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED;CN=BOB:mailto:creator@example.com
            ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED;CN=Alice:mailto:alice@example.com
            X-PUBLICLY-CREATED;VALUE=BOOLEAN:TRUE
            X-PUBLICLY-CREATOR:creator@example.com
            END:VEVENT
            END:VCALENDAR
            """));

        assertSoftly(softly -> {
            softly.assertThat(result.organizerPerson().cn()).isEqualTo("Alice Owner");
            softly.assertThat(result.organizerPerson().email().asString()).isEqualTo("owner@example.com");
            softly.assertThat(result.cancelledBy().cn()).isEqualTo("BOB");
            softly.assertThat(result.cancelledBy().email().asString()).isEqualTo("creator@example.com");
        });
    }

    @Test
    void fromShouldFallbackToOrganizerWhenPubliclyCreatorIsAbsent() {
        BookedEventCancelled result = BookedEventCancelled.from(ORGANIZER, CalendarUtil.parseIcs("""
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-123
            SUMMARY:30-min intro call
            DTSTART:20360126T093000Z
            DURATION:PT30M
            ORGANIZER;CN=Alice Owner:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;CN=Alice Owner:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED;CN=BOB:mailto:creator@example.com
            END:VEVENT
            END:VCALENDAR
            """));

        assertThat(result.cancelledBy())
            .isEqualTo(result.organizerPerson());
    }

    @Test
    void pugModelShouldIdentifyOrganizerAsNotBookerRecipient() throws Exception {
        Map<?, ?> event = eventPugModel(new MailAddress("owner@example.com"));

        assertThat(event.get(PublicAgendaCancellationNotifier.PugModel.RECIPIENT_IS_BOOKER))
            .isEqualTo(false);
    }

    @Test
    void pugModelShouldIdentifyBookerRecipient() throws Exception {
        Map<?, ?> event = eventPugModel(new MailAddress("creator@example.com"));

        assertThat(event.get(PublicAgendaCancellationNotifier.PugModel.RECIPIENT_IS_BOOKER))
            .isEqualTo(true);
    }

    private Map<?, ?> eventPugModel(MailAddress recipient) throws Exception {
        Map<String, Object> pugModel = PublicAgendaCancellationNotifier.PugModel.toPugModel(
            cancelledBooking(),
            recipient,
            Locale.ENGLISH,
            ZoneId.of("Europe/Paris"),
            new EventInCalendarLinkFactory(URI.create("http://localhost:3000/").toURL()));

        Map<?, ?> content = (Map<?, ?>) pugModel.get(PublicAgendaCancellationNotifier.PugModel.CONTENT);
        return (Map<?, ?>) content.get(PublicAgendaCancellationNotifier.PugModel.EVENT);
    }

    private BookedEventCancelled cancelledBooking() {
        return BookedEventCancelled.from(ORGANIZER, CalendarUtil.parseIcs("""
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-123
            SUMMARY:30-min intro call
            DTSTART:20360126T093000Z
            DURATION:PT30M
            ORGANIZER;CN=Alice Owner:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;CN=Alice Owner:mailto:owner@example.com
            ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED;CN=BOB:mailto:creator@example.com
            ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED;CN=Alice:mailto:alice@example.com
            X-PUBLICLY-CREATED;VALUE=BOOLEAN:TRUE
            X-PUBLICLY-CREATOR:creator@example.com
            X-PUBLICLY-CANCELLED-BY:creator@example.com
            END:VEVENT
            END:VCALENDAR
            """));
    }
}
