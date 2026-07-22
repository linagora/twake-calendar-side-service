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
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.TeamCalendarRepository;
import com.linagora.calendar.storage.UsernameRegistrationKey;
import com.linagora.calendar.storage.model.ResourceId;
import com.linagora.calendar.storage.model.TeamCalendarId;

import reactor.core.publisher.Mono;

public class CalendarListNotificationHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarListNotificationHandler.class);
    private static final Set<String> DELEGATED_ACCESS_TYPES = Set.of("dav:read-write", "dav:read", "dav:administration");

    private final EventBus eventBus;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final ResourceDAO resourceDAO;
    private final TeamCalendarRepository teamCalendarRepository;

    @Inject
    public CalendarListNotificationHandler(EventBus eventBus, OpenPaaSUserDAO openPaaSUserDAO, ResourceDAO resourceDAO,
                                           TeamCalendarRepository teamCalendarRepository) {
        this.resourceDAO = resourceDAO;
        this.eventBus = eventBus;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.teamCalendarRepository = teamCalendarRepository;
    }

    public Mono<Void> handle(CalendarListExchange exchange, CalendarListChangesMessage message) {
        CalendarURL calendarURL = message.calendarURL();
        ChangeType changeType = resolveChangeType(exchange, message);

        return openPaaSUserDAO.retrieve(calendarURL.base())
            .switchIfEmpty(Mono.defer(() -> ignoreKnownNonUserOwner(exchange, calendarURL)))
            .flatMap(user -> eventBus.dispatch(
                    CalendarListChangedEvent.of(user.username(), calendarURL, changeType),
                    new UsernameRegistrationKey(user.username()))
                .doOnSuccess(ignored -> LOGGER.debug("Published calendar list changed event {} for {}", changeType, calendarURL.asUri()))
                .then());
    }

    private Mono<OpenPaaSUser> ignoreKnownNonUserOwner(CalendarListExchange exchange, CalendarURL calendarURL) {
        return resourceDAO.findById(ResourceId.from(calendarURL.base()))
            .hasElement()
            .flatMap(isResource -> {
                if (isResource) {
                    LOGGER.debug("Ignore calendar list notification for resource calendar {}", calendarURL.asUri());
                    return Mono.empty();
                }
                return ignoreTeamCalendarOwner(exchange, calendarURL);
            });
    }

    private Mono<OpenPaaSUser> ignoreTeamCalendarOwner(CalendarListExchange exchange, CalendarURL calendarURL) {
        return teamCalendarRepository.retrieve(TeamCalendarId.from(calendarURL.base()))
            .hasElement()
            .flatMap(isTeamCalendar -> {
                if (isTeamCalendar) {
                    LOGGER.debug("Ignore calendar list notification for team calendar {}", calendarURL.asUri());
                    return Mono.empty();
                }
                if (exchange == CalendarListExchange.CALENDAR_DELETED) {
                    LOGGER.debug("Ignore deleted calendar list notification for unknown owner {}", calendarURL.asUri());
                    return Mono.empty();
                }
                return Mono.error(() -> new IllegalStateException("Can not resolve base id " + calendarURL.base().value()));
            });
    }

    private ChangeType resolveChangeType(CalendarListExchange exchange, CalendarListChangesMessage message) {
        return switch (exchange) {
            case CALENDAR_CREATED -> resolveCalendarCreatedChangeType(message);
            case CALENDAR_UPDATED, SUBSCRIPTION_UPDATED -> ChangeType.UPDATED;
            case CALENDAR_DELETED, SUBSCRIPTION_DELETED -> ChangeType.DELETED;
            case SUBSCRIPTION_CREATED -> ChangeType.SUBSCRIBED;
        };
    }

    private ChangeType resolveCalendarCreatedChangeType(CalendarListChangesMessage message) {
        return message.access()
            .filter(DELEGATED_ACCESS_TYPES::contains)
            .map(access -> ChangeType.DELEGATED)
            .orElse(ChangeType.CREATED);
    }

}
