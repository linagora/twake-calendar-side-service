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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Splitter;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CalendarMessageDTO(@JsonProperty("calendarPath") String calendarPath) {
    public CalendarURL extractCalendarURL() {
        if (!calendarPath.startsWith(CalendarURL.CALENDAR_URL_PATH_PREFIX)) {
            throw new CalendarEventDeserializeException("Invalid event path: " + calendarPath);
        }
        List<String> paths = Splitter.on('/')
            .omitEmptyStrings()
            .splitToList(calendarPath);

        if (paths.size() != 3) {
            throw new CalendarEventDeserializeException("Invalid event path: " + calendarPath);
        }
        return new CalendarURL(new OpenPaaSId(paths.get(1)), new OpenPaaSId(paths.get(2)));
    }
}
