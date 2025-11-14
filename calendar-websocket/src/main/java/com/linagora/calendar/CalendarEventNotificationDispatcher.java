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

package com.linagora.calendar;

import org.apache.james.core.Username;
import org.apache.james.events.Event;
import org.apache.james.events.EventBus;

import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class CalendarEventNotificationDispatcher {

    private final EventBus eventBus;
    private final OpenPaaSUserDAO userDAO;

    public CalendarEventNotificationDispatcher(EventBus eventBus, OpenPaaSUserDAO userDAO) {
        this.eventBus = eventBus;
        this.userDAO = userDAO;
    }

    public Mono<Void> dispatch(CalendarURL calendarURL, String path, String action) {
        // list all subscription calendar + delegatedCalendar
        return getSubscriptionCalendar(calendarURL)
            .flatMap(userDAO::retrieve)
            .flatMap(user -> dispatch(user.username(), path, action))
            .then();
    }

    private Flux<OpenPaaSId> getSubscriptionCalendar(CalendarURL calendarURL) {
        // TODO
        return Flux.empty();
    }

    public Mono<Void> dispatch(Username username, String path, String action) {
        UsernameRegistrationKey usernameRegistrationKey = new UsernameRegistrationKey(username);
        Event.EventId eventId = Event.EventId.random();
        Event event = new CalendarChangeEvent(eventId, username, path, action);
        return Mono.from(eventBus.dispatch(event, usernameRegistrationKey));
    }
}
