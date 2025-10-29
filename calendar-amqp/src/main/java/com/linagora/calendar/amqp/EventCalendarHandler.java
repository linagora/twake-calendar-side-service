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

import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import reactor.core.publisher.Mono;

public class EventCalendarHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventCalendarHandler.class);

    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final CalDavClient calDavClient;
    
    @Inject
    public EventCalendarHandler(OpenPaaSUserDAO openPaaSUserDAO, CalDavClient calDavClient) {
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.calDavClient = calDavClient;
    }

    public Mono<Void> handleCreateEvent(CalendarMessageDTO message) {
        LOGGER.debug("Handle creating calendar event with calendar path {}", message.calendarPath());
        return setDefaultCalendarVisible(message);
    }

    private Mono<Void> setDefaultCalendarVisible(CalendarMessageDTO message) {
        CalendarURL calendarURL = message.extractCalendarURL();
        return openPaaSUserDAO.retrieve(calendarURL.base())
            .filter(openPaaSUser -> openPaaSUser.id().equals(calendarURL.calendarId()))
            .flatMap(openPaaSUser -> calDavClient.updateCalendarAcl(openPaaSUser, CalDavClient.PublicRight.READ));
    }
}
