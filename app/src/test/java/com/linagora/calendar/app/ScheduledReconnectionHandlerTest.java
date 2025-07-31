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

package com.linagora.calendar.app;

import static com.linagora.calendar.dav.DavModuleTestHelper.FROM_SABRE_EXTENSION;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.apache.james.utils.GuiceProbe;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.google.common.collect.ImmutableList;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.modules.ScheduledReconnectionHandler;
import com.linagora.calendar.app.modules.ScheduledReconnectionHandler.RabbitMQManagementAPI;
import com.linagora.calendar.app.modules.ScheduledReconnectionHandler.ScheduledReconnectionHandlerConfiguration;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.amqp.EventIndexerConsumer;

public class ScheduledReconnectionHandlerTest {
    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(30, TimeUnit.SECONDS);

    static class ScheduledReconnectionHandlerProbe implements GuiceProbe {
        private final ScheduledReconnectionHandler scheduledReconnectionHandler;
        private final EventIndexerConsumer davCalendarEventConsumer;

        @Inject
        public ScheduledReconnectionHandlerProbe(ScheduledReconnectionHandler scheduledReconnectionHandler, EventIndexerConsumer davCalendarEventConsumer) {
            this.scheduledReconnectionHandler = scheduledReconnectionHandler;
            this.davCalendarEventConsumer = davCalendarEventConsumer;
        }

        public ImmutableList<String> getQueuesToMonitor() {
            return scheduledReconnectionHandler.getQueuesToMonitor();
        }
    }

    static {
        System.setProperty("scheduled.consumer.reconnection.delayStartUp", "10s");
    }

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    @RegisterExtension
    @Order(2)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        binder -> {
            Multibinder.newSetBinder(binder, GuiceProbe.class).addBinding().to(ScheduledReconnectionHandlerProbe.class);
            binder.bind(ScheduledReconnectionHandlerConfiguration.class)
                .toInstance(new ScheduledReconnectionHandlerConfiguration(true, Duration.ofSeconds(2)));
        });


    @Test
    void shouldRestartDavCalendarEventConsumerWhenConsumerDisconnected(TwakeCalendarGuiceServer server) {
        RabbitMQManagementAPI rabbitMQManagementAPI = RabbitMQManagementAPI.from(sabreDavExtension.dockerSabreDavSetup().rabbitMQConfiguration());

        String queueName = EventIndexerConsumer.Queue.ADD.queueName();

        awaitAtMost
            .untilAsserted(() -> assertThat(rabbitMQManagementAPI.queueDetails("/", queueName)
                .consumerDetails()).hasSize(1));

        String consumerNameTag = rabbitMQManagementAPI.queueDetails("/", queueName).consumerDetails().getFirst().tag();

        // try to close the consumer
        server.getProbe(ScheduledReconnectionHandlerProbe.class).davCalendarEventConsumer.close();

        // then ReconnectionHandler will restart the consumer
        awaitAtMost
            .untilAsserted(() -> {
                List<RabbitMQManagementAPI.ConsumerDetails> consumerDetails = rabbitMQManagementAPI.queueDetails("/", queueName)
                    .consumerDetails();
                assertThat(consumerDetails).hasSize(1);
                assertThat(consumerDetails.getFirst().tag()).isNotEqualTo(consumerNameTag);
            });
    }

    @Test
    void shouldMonitorDavCalendarEventQueues(TwakeCalendarGuiceServer server) {
        assertThat(server.getProbe(ScheduledReconnectionHandlerProbe.class).getQueuesToMonitor())
            .contains("tcalendar:event:created:search",
                "tcalendar:event:updated:search",
                "tcalendar:event:deleted:search",
                "tcalendar:event:cancel:search",
                "tcalendar:event:request:search",
                "tcalendar:event:alarm:created",
                "tcalendar:event:alarm:updated",
                "tcalendar:event:alarm:deleted",
                "tcalendar:event:alarm:cancel",
                "tcalendar:event:alarm:request",
                "tcalendar:event:notificationEmail:send");
    }
}