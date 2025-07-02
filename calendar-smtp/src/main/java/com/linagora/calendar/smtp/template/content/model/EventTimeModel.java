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

package com.linagora.calendar.smtp.template.content.model;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

public record EventTimeModel(ZonedDateTime dateTime) {

    public Map<String, Object> toPugModel(Locale locale) {
        return Map.of(
            "date", format("yyyy-MM-dd", locale),
            "time", format("HH:mm", locale),
            "fullDate", format("EEEE, dd MMMM yyyy", locale),
            "fullDateTime", format("EEEE, dd MMMM yyyy HH:mm", locale),
            "timezone", dateTime.getZone().getId());
    }

    private String format(String pattern, Locale locale) {
        return dateTime.format(DateTimeFormatter.ofPattern(pattern, locale));
    }
}
