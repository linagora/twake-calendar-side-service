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

package com.linagora.calendar.storage;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.linagora.calendar.storage.eventsearch.EventUid;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MemoryAlarmEventDAO implements AlarmEventDAO {
    private final Map<String, AlarmEvent> store = new ConcurrentHashMap<>();

    @Override
    public Mono<AlarmEvent> find(EventUid eventUid) {
        return Mono.fromCallable(() -> store.get(eventUid.value()));
    }

    @Override
    public Mono<Void> create(AlarmEvent alarmEvent) {
        return Mono.fromRunnable(() -> store.put(alarmEvent.eventUid().value(), alarmEvent));
    }

    @Override
    public Mono<Void> update(AlarmEvent alarmEvent) {
        return Mono.fromRunnable(() -> store.put(alarmEvent.eventUid().value(), alarmEvent));
    }

    @Override
    public Mono<Void> delete(EventUid eventUid) {
        return Mono.fromRunnable(() -> store.remove(eventUid.value()));
    }

    @Override
    public Flux<AlarmEvent> findBetweenAlarmTimeAndStartTime(Instant time) {
        return Flux.fromStream(store.values().stream()
            .filter(e -> !e.alarmTime().isAfter(time) && time.isBefore(e.eventStartTime())));
    }
}
