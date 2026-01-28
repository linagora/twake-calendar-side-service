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

import java.util.Set;

import jakarta.inject.Inject;

import org.apache.james.events.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.amqp.CalendarListNotificationConsumer.CalendarListChangesMessage;
import com.linagora.calendar.amqp.CalendarListNotificationConsumer.CalendarListExchange;
import com.linagora.calendar.storage.CalendarListChangedEvent;
import com.linagora.calendar.storage.CalendarListChangedEvent.ChangeType;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.UsernameRegistrationKey;

import reactor.core.publisher.Mono;

public class CalendarListNotificationHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarListNotificationHandler.class);
    private static final Set<String> DELEGATED_ACCESS_TYPES = Set.of("dav:read-write", "dav:read", "dav:administration");

    private final EventBus eventBus;
    private final OpenPaaSUserDAO openPaaSUserDAO;

    @Inject
    public CalendarListNotificationHandler(EventBus eventBus, OpenPaaSUserDAO openPaaSUserDAO) {
        this.eventBus = eventBus;
        this.openPaaSUserDAO = openPaaSUserDAO;
    }

    public Mono<Void> handle(CalendarListExchange exchange, CalendarListChangesMessage message) {
        CalendarURL calendarURL = message.calendarURL();
        ChangeType changeType = resolveChangeType(exchange, message);

        return openPaaSUserDAO.retrieve(calendarURL.base())
            .switchIfEmpty(Mono.error(new IllegalStateException("OpenPaaS user not found for id " + calendarURL.base().value())))
            .flatMap(user -> eventBus.dispatch(
                CalendarListChangedEvent.of(user.username(), calendarURL, changeType),
                    new UsernameRegistrationKey(user.username()))
                .doOnSuccess(ignored -> LOGGER.debug("Published calendar list changed event {} for {}", changeType, calendarURL.asUri()))
                .then());
    }

    private ChangeType resolveChangeType(CalendarListExchange exchange, CalendarListChangesMessage message) {
        return switch (exchange) {
            case CALENDAR_CREATED -> resolveCalendarCreatedChangeType(message);
            case CALENDAR_UPDATED -> ChangeType.UPDATED;
            case CALENDAR_DELETED -> ChangeType.DELETED;
            case SUBSCRIPTION_CREATED -> ChangeType.SUBSCRIBED;
            case SUBSCRIPTION_UPDATED -> ChangeType.UPDATED; // TODO
            case SUBSCRIPTION_DELETED -> ChangeType.RIGHTS_REVOKED;
        };
    }

    private ChangeType resolveCalendarCreatedChangeType(CalendarListChangesMessage message) {
        return message.access()
            .filter(DELEGATED_ACCESS_TYPES::contains)
            .map(access -> ChangeType.DELEGATED)
            .orElse(ChangeType.CREATED);
    }

}
