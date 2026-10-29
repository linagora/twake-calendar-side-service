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

package com.linagora.calendar.amqp.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO;
import com.linagora.calendar.api.CalendarUtil;

import net.fortuna.ical4j.model.property.Method;

class CalendarEventReplyNotificationEmailTest {

    private static final String REPLY_WITHOUT_ATTENDEE = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Calendar//EN
        METHOD:REPLY
        BEGIN:VEVENT
        UID:reply-uid@test
        DTSTART:20261026T100000Z
        DTEND:20261026T110000Z
        SUMMARY:Sync
        ORGANIZER:mailto:bob@example.com
        END:VEVENT
        END:VCALENDAR
        """;

    private static final String REPLY_WITH_SEVERAL_ATTENDEES = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Calendar//EN
        METHOD:REPLY
        BEGIN:VEVENT
        UID:reply-uid@test
        DTSTART:20261026T100000Z
        DTEND:20261026T110000Z
        SUMMARY:Sync
        ORGANIZER:mailto:bob@example.com
        ATTENDEE;CN=Other:mailto:other@example.com
        ATTENDEE;PARTSTAT=ACCEPTED;CN=Attendee:mailto:attendee@example.com
        END:VEVENT
        END:VCALENDAR
        """;

    private static CalendarEventNotificationEmailDTO replyDto(String ics) throws Exception {
        return new CalendarEventNotificationEmailDTO(
            new MailAddress("attendee@example.com"),
            new MailAddress("bob@example.com"),
            new Method(Method.VALUE_REPLY),
            CalendarUtil.parseIcs(ics),
            "/calendars/bob/events/reply-uid.ics",
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    }

    @Test
    void toReplyContentModelBuilderShouldFailWithADocumentedErrorWhenNoAttendee() throws Exception {
        CalendarEventNotificationEmailDTO dto = replyDto(REPLY_WITHOUT_ATTENDEE);

        assertThatThrownBy(() -> CalendarEventReplyNotificationEmail.from(dto).toReplyContentModelBuilder())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("No attendee in REPLY");
    }

    @Test
    void toReplyContentModelBuilderShouldPickTheAttendeeMatchingTheSender() throws Exception {
        CalendarEventNotificationEmailDTO dto = replyDto(REPLY_WITH_SEVERAL_ATTENDEES);

        // The first attendee has no PARTSTAT: picking it would fail
        assertThatCode(() -> CalendarEventReplyNotificationEmail.from(dto).toReplyContentModelBuilder())
            .doesNotThrowAnyException();
    }
}
