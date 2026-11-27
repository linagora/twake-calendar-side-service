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

package com.linagora.calendar.storage.eventtoken;

import com.linagora.calendar.storage.CalendarURL;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Stores event tokens. Expired tokens are never returned.
 */
public interface EventTokenDAO {

    Mono<Void> insert(EventToken eventToken);

    Mono<EventToken> findById(EventTokenId id);

    Flux<EventToken> listByEvent(CalendarURL calendarURL, String eventId);

    Mono<Void> delete(EventTokenId id);
}
