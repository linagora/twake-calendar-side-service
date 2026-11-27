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

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Inject;

import com.linagora.calendar.storage.CalendarURL;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MemoryEventTokenDAO implements EventTokenDAO {

    private final Map<EventTokenId, EventToken> store;
    private final Clock clock;

    @Inject
    public MemoryEventTokenDAO(Clock clock) {
        this.clock = clock;
        this.store = new ConcurrentHashMap<>();
    }

    @Override
    public Mono<Void> insert(EventToken eventToken) {
        return Mono.fromRunnable(() -> store.put(eventToken.id(), eventToken));
    }

    @Override
    public Mono<EventToken> findById(EventTokenId id) {
        return Mono.justOrEmpty(store.get(id))
            .filter(eventToken -> !eventToken.isExpiredAt(clock.instant()));
    }

    @Override
    public Flux<EventToken> listByEvent(CalendarURL calendarURL, String eventId) {
        return Flux.fromIterable(store.values())
            .filter(eventToken -> eventToken.calendarURL().equals(calendarURL) && eventToken.eventId().equals(eventId))
            .filter(eventToken -> !eventToken.isExpiredAt(clock.instant()));
    }

    @Override
    public Mono<Void> delete(EventTokenId id) {
        return Mono.fromRunnable(() -> store.remove(id));
    }
}
