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

import org.apache.james.core.MailAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;

class HeaderAddressValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"bob@domain.tld", "bob+calendar@domain.tld", "\"a\\b\"@domain.tld"})
    void shouldAcceptAddressWritableInAHeader(String address) {
        assertThat(HeaderAddressValidator.isWritableInHeader(address)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "pro", "a\\b@domain.tld", "a\\:b@domain.tld", "a\\\rb@domain.tld", "a\\\nb@domain.tld"})
    void shouldRejectAddressNotWritableInAHeader(String address) {
        assertThat(HeaderAddressValidator.isWritableInHeader(address)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mailto:a\\b@domain.tld?subject=x", "mailto:a%5Cb@domain.tld?subject=x", "mailto:a%5C%0Db@domain.tld?subject=x"})
    void shouldRejectIcsOrganizerAcceptedByMailAddressButNotWritableInAHeader(String calAddress) {
        Calendar calendar = CalendarUtil.parseIcs("""
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            BEGIN:VEVENT
            UID:abc
            DTSTART:30250411T100000Z
            ORGANIZER;CN=Van Tung TRAN:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(calAddress));
        VEvent vEvent = (VEvent) calendar.getComponent(VEvent.VEVENT).orElseThrow();
        MailAddress organizer = EventParseUtils.getOrganizer(vEvent).orElseThrow().email();

        assertThat(HeaderAddressValidator.isWritableInHeader(organizer)).isFalse();
    }
}
