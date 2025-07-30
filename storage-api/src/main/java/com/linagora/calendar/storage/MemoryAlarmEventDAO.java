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

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MemoryAlarmEventDAO implements AlarmEventDAO {
    private final Map<String, AlarmEvent> store = new ConcurrentHashMap<>();

    @Override
    public Mono<AlarmEvent> find(String id) {
        return Mono.fromCallable(() -> store.get(id));
    }

    @Override
    public Mono<Void> create(AlarmEvent alarmEvent) {
        return Mono.fromRunnable(() -> store.put(alarmEvent.eventId().value(), alarmEvent));
    }

    @Override
    public Mono<Void> update(AlarmEvent alarmEvent) {
        return Mono.fromRunnable(() -> store.put(alarmEvent.eventId().value(), alarmEvent));
    }

    @Override
    public Mono<Void> delete(String id) {
        return Mono.fromRunnable(() -> store.remove(id));
    }

    @Override
    public Flux<AlarmEvent> getByTime(Instant time) {
        return Flux.fromStream(store.values().stream()
            .filter(e -> !e.alarmTime().isAfter(time)));
    }
}
