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

package com.linagora.calendar.storage.redis;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.james.events.Event;
import org.apache.james.events.EventBus;
import org.apache.james.events.EventBusName;
import org.apache.james.events.EventListener;
import org.apache.james.events.EventSerializer;
import org.apache.james.events.Group;
import org.apache.james.events.Registration;
import org.apache.james.events.RegistrationKey;

import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.lettuce.core.pubsub.api.reactive.RedisPubSubReactiveCommands;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class CalendarRedisEventBus implements EventBus {

    private final RedisReactiveCommands<String, String> redisCmd;
    private final RedisPubSubReactiveCommands<String, String> redisPubSub;

    private final EventSerializer serializer;

    private final Map<RegistrationKey, EventListener.ReactiveEventListener> localListeners = new ConcurrentHashMap<>();

    public CalendarRedisEventBus(RedisReactiveCommands<String, String> redisCmd,
                                 RedisPubSubReactiveCommands<String, String> redisPubSub,
                                 EventSerializer serializer) {
        this.redisCmd = redisCmd;
        this.redisPubSub = redisPubSub;
        this.serializer = serializer;
    }

    @Override
    public Mono<Registration> register(EventListener.ReactiveEventListener listener,
                                       RegistrationKey key) {

        localListeners.put(key, listener);
        String channel = redisChannel(key);
        return redisPubSub.subscribe(channel)
            .then(Mono.just(() -> redisPubSub.unsubscribe(channel)
                .then(Mono.fromRunnable(() -> localListeners.remove(key)))));
    }

    @Override
    public Registration register(EventListener.ReactiveEventListener listener,
                                 Group group) {
        throw new UnsupportedOperationException("CalendarRedisEventBus does not support Group registration");
    }

    @Override
    public Mono<Void> dispatch(Event event, Set<RegistrationKey> keys) {
        if (event.isNoop()) {
            return Mono.empty();
        }

        String payload = serializer.toJson(event);
        return Flux.fromIterable(keys)
            .flatMap(key -> redisCmd.publish(redisChannel(key), payload))
            .then();
    }

    @Override
    public Mono<Void> reDeliver(Group group, Event event) {
        return Mono.empty();
    }

    @Override
    public EventBusName eventBusName() {
        return new EventBusName("calendar-redis-eventbus");
    }

    private String redisChannel(RegistrationKey key) {
        return "calendar:notification:" + key.asString();
    }
}