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

import reactor.core.publisher.Mono;

public class EventResourceHandler {

    public Mono<Void> handleCreateEvent(CalendarResourceMessageDTO message) {
        return Mono.empty();
    }

    public Mono<Void> handleAcceptEvent(CalendarResourceMessageDTO message) {
        return Mono.empty();
    }

    public Mono<Void> handleDeclineEvent(CalendarResourceMessageDTO message) {
        return Mono.empty();
    }
}
