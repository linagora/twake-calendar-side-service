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

import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.api.CalendarUtil;
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
}
