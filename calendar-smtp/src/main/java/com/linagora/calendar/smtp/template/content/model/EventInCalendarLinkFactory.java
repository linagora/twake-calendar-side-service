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

import java.net.URI;
import java.net.URL;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.StringUtils;

public class EventInCalendarLinkFactory {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd-yyyy");
    private final Function<ZonedDateTime, URI> calendarBaseUrlFunction;

    @Inject
    @Singleton
    public EventInCalendarLinkFactory(@Named("spaCalendarUrl") URL calendarBaseUrl)   {
        this.calendarBaseUrlFunction = buildCalendarLinkFunction(calendarBaseUrl);
    }

    private Function<ZonedDateTime, URI> buildCalendarLinkFunction(URL baseUrl) {
        String base = baseUrl.toString();
        return date -> URI.create(StringUtils.removeEnd(base, "/") + "/calendar/#/calendar?start=" + DATE_TIME_FORMATTER.format(date));
    }

    public String getEventInCalendarLink(ZonedDateTime startDate) {
        return calendarBaseUrlFunction.apply(startDate)
            .toString();
    }
}
