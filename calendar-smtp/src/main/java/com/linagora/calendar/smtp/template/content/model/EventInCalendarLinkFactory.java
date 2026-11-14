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

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.Strings;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;

import com.linagora.calendar.api.DomainUrl;

public class EventInCalendarLinkFactory {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd-yyyy");
    private final DomainUrl calendarBaseUrl;

    @Inject
    @Singleton
    public EventInCalendarLinkFactory(@Named("spaCalendarUrl") DomainUrl calendarBaseUrl)   {
        this.calendarBaseUrl = calendarBaseUrl;
    }

    public EventInCalendarLinkFactory(URL calendarBaseUrl) {
        this(DomainUrl.of(calendarBaseUrl));
    }

    /**
     * Binds the calendar URL, which may contain the {@value DomainUrl#DOMAIN_PLACEHOLDER} placeholder, to the given domain.
     */
    public EventInCalendarLinkFactory forDomain(Domain domain) {
        return new EventInCalendarLinkFactory(calendarBaseUrl.resolve(domain));
    }

    public EventInCalendarLinkFactory forUser(Username username) {
        return new EventInCalendarLinkFactory(calendarBaseUrl.resolve(username));
    }

    /**
     * @throws IllegalStateException if the calendar URL contains the placeholder and was not bound to a domain
     */
    public String getEventInCalendarLink(ZonedDateTime startDate) {
        String base = calendarBaseUrl.asUrl().toString();
        return URI.create(Strings.CS.removeEnd(base, "/") + "/calendar/#/calendar?start=" + DATE_TIME_FORMATTER.format(startDate))
            .toString();
    }
}
