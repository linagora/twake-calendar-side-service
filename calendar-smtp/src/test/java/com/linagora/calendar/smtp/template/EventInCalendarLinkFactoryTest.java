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

package com.linagora.calendar.smtp.template;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;

public class EventInCalendarLinkFactoryTest {

    @Test
    void testGetEventInCalendarLink() throws MalformedURLException {
        EventInCalendarLinkFactory testee = new EventInCalendarLinkFactory(URI.create("http://localhost:3000/").toURL());

        String eventInCalendarLink = testee.getEventInCalendarLink(ZonedDateTime.parse("2023-10-01T10:00:00Z"));
        assertThat(eventInCalendarLink)
            .isEqualTo("http://localhost:3000/calendar/#/calendar?start=10-01-2023");

    }
}
