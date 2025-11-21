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

package org.apache.james.events;

import static org.apache.james.events.EventBusTestFixture.EVENT;
import static org.apache.james.events.EventBusTestFixture.KEY_1;
import static org.apache.james.events.EventBusTestFixture.newListener;
import static org.apache.james.events.RedisEventBusConfiguration.FAILURE_IGNORE_DEFAULT;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Durations.ONE_HUNDRED_MILLISECONDS;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.james.backends.redis.RedisClientFactory;
import org.apache.james.backends.redis.RedisConfiguration;
import org.apache.james.backends.redis.RedisExtension;
import org.apache.james.backends.redis.StandaloneRedisConfiguration;
import org.apache.james.events.KeyContract.MultipleEventBusKeyContract;
import org.apache.james.events.KeyContract.SingleEventBusKeyContract;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.assertj.core.api.SoftAssertions;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableSet;

import reactor.core.publisher.Mono;

public class CalendarRedisEventBusTest implements SingleEventBusKeyContract, MultipleEventBusKeyContract {
    ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(ONE_HUNDRED_MILLISECONDS)
        .and()
        .with()
        .pollDelay(ONE_HUNDRED_MILLISECONDS)
        .await();
    ConditionFactory awaitAtMostThirtySeconds = calmlyAwait.atMost(Duration.ofSeconds(30));

    @RegisterExtension
    static RedisExtension redisExtension = new RedisExtension();

    private RedisEventBusClientFactory redisEventBusClientFactory;
    private CalendarRedisEventBus eventBus;
    private CalendarRedisEventBus eventBus2;
    private EventSerializer eventSerializer;
    private RoutingKeyConverter routingKeyConverter;
    private RedisClientFactory redisClientFactory;

    @BeforeEach
    void setUp() throws Exception {
        redisClientFactory = new RedisClientFactory(FileSystemImpl.forTesting(), redisConfiguration());
        redisEventBusClientFactory = new RedisEventBusClientFactory(redisConfiguration(), redisClientFactory);

        eventSerializer = new EventBusTestFixture.TestEventSerializer();
        routingKeyConverter = RoutingKeyConverter.forFactories(new EventBusTestFixture.TestRegistrationKeyFactory());

        eventBus = newEventBus();
        eventBus2 = newEventBus();

        eventBus.start();
        eventBus2.start();
    }

    public void pauseRedis() {
        redisExtension.dockerRedis().pause();
    }

    public void unpauseRedis() {
        redisExtension.dockerRedis().unPause();
    }

    @Override
    public EventBus eventBus() {
        return eventBus;
    }

    @Override
    public EventBus eventBus2() {
        return eventBus2;
    }

    @Override
    public EnvironmentSpeedProfile getSpeedProfile() {
        return EnvironmentSpeedProfile.SLOW;
    }

    RedisConfiguration redisConfiguration() {
        return StandaloneRedisConfiguration.from(redisExtension.dockerRedis().redisURI().toString());
    }

    private CalendarRedisEventBus newEventBus() {
        return new CalendarRedisEventBus(eventSerializer,
            EventBusTestFixture.RETRY_BACKOFF_CONFIGURATION, routingKeyConverter,
            new RecordingMetricFactory(),
            EventBusId.random(),
            redisEventBusClientFactory,
            new RedisEventBusConfiguration(FAILURE_IGNORE_DEFAULT,
                Duration.ofSeconds(2)));
    }

    @Override
    @Test
    @Disabled("This test is failing by design as the different registration keys are handled by distinct messages")
    public void dispatchShouldCallListenerOnceWhenSeveralKeysMatching() {

    }

    @Test
    void dispatchShouldWorkAfterNetworkIssuesForOldKeyRegistration() {
        eventBus.start();
        EventListener listener = newListener();
        when(listener.getExecutionMode()).thenReturn(EventListener.ExecutionMode.ASYNCHRONOUS);
        Mono.from(eventBus.register(listener, KEY_1)).block();

        pauseRedis();

        assertThatThrownBy(() -> eventBus.dispatch(EVENT, KEY_1).block())
            .getCause()
            .isInstanceOf(TimeoutException.class);

        unpauseRedis();

        eventBus.dispatch(EVENT, KEY_1).block();
        awaitAtMostThirtySeconds
            .untilAsserted(() -> verify(listener).event(EVENT));
    }

    @Test
    void dispatchShouldWorkAfterNetworkIssuesForNewKeyRegistration() {
        eventBus.start();
        EventListener listener = newListener();
        when(listener.getExecutionMode()).thenReturn(EventListener.ExecutionMode.ASYNCHRONOUS);

        pauseRedis();

        assertThatThrownBy(() -> eventBus.dispatch(EVENT, KEY_1).block())
            .getCause()
            .isInstanceOf(TimeoutException.class);

        unpauseRedis();

        Mono.from(eventBus.register(listener, KEY_1)).block();
        eventBus.dispatch(EVENT, KEY_1).block();
        awaitAtMostThirtySeconds
            .untilAsserted(() -> verify(listener).event(EVENT));
    }

    @Test
    void eventBusPubSubWithDistinctNamingStrategiesShouldBeIsolated() throws Exception {
        EventCollector listener = new EventCollector();
        EventCollector otherListener = new EventCollector();
        eventBus.register(listener, KEY_1);
        eventBus2.register(otherListener, KEY_1);

        eventBus.dispatch(EVENT, ImmutableSet.of(KEY_1)).block();

        TimeUnit.SECONDS.sleep(1);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(listener.getEvents()).hasSize(1);
            softly.assertThat(otherListener.getEvents()).isEmpty();
        });
    }
}
