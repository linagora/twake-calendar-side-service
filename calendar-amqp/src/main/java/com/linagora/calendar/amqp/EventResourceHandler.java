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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Mono;

public class EventResourceHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventResourceHandler.class);

    public Mono<Void> handleCreateEvent(CalendarResourceMessageDTO message) {
        LOGGER.debug("Handle create event with resource message containing resourceId {} and eventPath {}", message.resourceId(), message.eventPath());
        return Mono.empty();
    }

    public Mono<Void> handleAcceptEvent(CalendarResourceMessageDTO message) {
        LOGGER.debug("Handle accept event with resource message containing resourceId {} and eventPath {}", message.resourceId(), message.eventPath());
        return Mono.empty();
    }

    public Mono<Void> handleDeclineEvent(CalendarResourceMessageDTO message) {
        LOGGER.debug("Handle decline event with resource message containing resourceId {} and eventPath {}", message.resourceId(), message.eventPath());
        return Mono.empty();
    }
}
