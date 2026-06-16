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

package com.linagora.calendar.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.amqp.model.CalendarEventNotificationEmail;
import com.linagora.calendar.api.CalendarUtil;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.property.Method;

class OrganizerAbsentActionLinksTest {

    private static final String ICS_WITHOUT_ORGANIZER = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        METHOD:REQUEST
        BEGIN:VEVENT
        UID:uid-no-organizer@test
        DTSTART:20260401T100000Z
        DTEND:20260401T110000Z
        SUMMARY:Team Meeting
        ATTENDEE;CN=Bob Attendee;PARTSTAT=ACCEPTED:mailto:bob@domain.tld
        END:VEVENT
        END:VCALENDAR
        """;

    private static CalendarEventNotificationEmail notificationEmail() throws Exception {
        Calendar calendar = CalendarUtil.parseIcs(ICS_WITHOUT_ORGANIZER);
        return new CalendarEventNotificationEmail(
            new MailAddress("sender@domain.tld"),
            new MailAddress("bob@domain.tld"),
            new Method(Method.VALUE_REQUEST),
            calendar,
            true,
            "/calendars/uri",
            "/calendars/base/event.ics");
    }

    @Test
    void generateActionLinksShouldBeEmptyWhenOrganizerIsAbsent() throws Exception {
        // No organizer means no participation reply destination: the action links are skipped
        // (rather than throwing), so the invite/update e-mail can still be generated.
        assertThat(EventMailHandler.EventMessageGenerator.generateActionLinks(null, notificationEmail()).block())
            .isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void baseEventModelShouldExposeEmptyOrganizerWhenOrganizerIsAbsent() throws Exception {
        Map<String, Object> eventModel = notificationEmail().toPugModel(Locale.ENGLISH, ZoneId.of("UTC"));

        assertThat((Map<String, Object>) eventModel.get("organizer"))
            .containsEntry("cn", "")
            .containsEntry("email", "");
    }
}
