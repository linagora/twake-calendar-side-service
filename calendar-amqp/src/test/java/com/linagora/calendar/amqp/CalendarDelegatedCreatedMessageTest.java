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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.nio.charset.StandardCharsets;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.amqp.CalendarDelegatedNotificationConsumer.CalendarDelegatedCreatedMessage;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;

public class CalendarDelegatedCreatedMessageTest {

    @Test
    void deserializeShouldSucceedWhenDoesDelegatedMessage() {
        String json = """
            {
                "calendarPath": "\\/calendars\\/67e26ebbecd9f300255a9f80\\/dafa1cb9-17c6-40fa-bd0c-4da06f2faf17",
                "calendarProps": {
                    "access": "dav:read-write"
                }
            }
            """;
        CalendarDelegatedCreatedMessage message = CalendarDelegatedCreatedMessage.deserialize(json.getBytes(StandardCharsets.UTF_8));

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(message.calendarURL()).isEqualTo(new CalendarURL(new OpenPaaSId("67e26ebbecd9f300255a9f80"), new OpenPaaSId("dafa1cb9-17c6-40fa-bd0c-4da06f2faf17")));
            softly.assertThat(message.rightKey().get()).isEqualTo("dav:read-write");
        });
    }

    @Test
    void deserializeShouldSucceedWhenDoesNotDelegatedMessage() {
        String json = """
            {
              "calendarPath": "/calendars/69704ec60dbc345e7d54b997/69704ec60dbc345e7d54b997",
              "calendarProps": {
                "{http://calendarserver.org/ns/}getctag": "http://sabre.io/ns/sync/1",
                "{http://sabredav.org/ns}sync-token": 1,
                "{urn:ietf:params:xml:ns:caldav}supported-calendar-component-set": {},
                "{urn:ietf:params:xml:ns:caldav}schedule-calendar-transp": {},
                "{DAV:}displayname": "#default"
              }
            }
            """;
        CalendarDelegatedCreatedMessage message = CalendarDelegatedCreatedMessage.deserialize(json.getBytes(StandardCharsets.UTF_8));
        assertThat(message.rightKey()).isEmpty();
    }
}
