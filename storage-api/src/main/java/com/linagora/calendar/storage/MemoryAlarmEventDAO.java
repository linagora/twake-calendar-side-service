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

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.core.MailAddress;

import com.linagora.calendar.storage.eventsearch.EventUid;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MemoryAlarmEventDAO implements AlarmEventDAO {

    @Inject
    @Singleton
    public MemoryAlarmEventDAO() {
    }

    private final Map<String, AlarmEvent> store = new ConcurrentHashMap<>();

    @Override
    public Mono<AlarmEvent> find(EventUid eventUid, MailAddress recipient) {
        return Mono.fromCallable(() -> store.get(generateKey(eventUid, recipient)));

    }

    @Override
    public Mono<Void> create(AlarmEvent alarmEvent) {
        return Mono.fromRunnable(() -> store.put(generateKey(alarmEvent.eventUid(), alarmEvent.recipient()), alarmEvent));
    }

    @Override
    public Mono<Void> update(AlarmEvent alarmEvent) {
        return Mono.fromRunnable(() -> store.put(generateKey(alarmEvent.eventUid(), alarmEvent.recipient()), alarmEvent));
    }

    @Override
    public Mono<Void> delete(EventUid eventUid, MailAddress recipient) {
        return Mono.fromRunnable(() -> store.remove(generateKey(eventUid, recipient)));
    }

    @Override
    public Flux<AlarmEvent> findAlarmsToTrigger(Instant time) {
        return Flux.fromStream(store.values().stream()
            .filter(e -> !e.alarmTime().isAfter(time)));
    }

    private String generateKey(EventUid eventUid, MailAddress recipient) {
        return eventUid.value() + ":" + recipient.asString();
    }
}
