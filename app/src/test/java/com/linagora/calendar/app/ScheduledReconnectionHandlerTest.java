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

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import jakarta.inject.Inject;

import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.utils.GuiceProbe;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.google.common.collect.ImmutableList;
import com.google.inject.Module;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.util.Modules;
import com.linagora.calendar.app.modules.ScheduledReconnectionHandler;
import com.linagora.calendar.app.modules.ScheduledReconnectionHandler.RabbitMQManagementAPI;
import com.linagora.calendar.app.modules.ScheduledReconnectionHandler.ScheduledReconnectionHandlerConfiguration;
import com.linagora.calendar.app.modules.TwakeCalendarRabbitMQModule;
import com.linagora.calendar.dav.DavConfiguration;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.amqp.DavCalendarEventConsumer;
import com.linagora.calendar.storage.mongodb.MongoDBConfiguration;

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
        private final DavCalendarEventConsumer davCalendarEventConsumer;

        @Inject
        public ScheduledReconnectionHandlerProbe(ScheduledReconnectionHandler scheduledReconnectionHandler, DavCalendarEventConsumer davCalendarEventConsumer) {
            this.scheduledReconnectionHandler = scheduledReconnectionHandler;
            this.davCalendarEventConsumer = davCalendarEventConsumer;
        }

        public ImmutableList<String> getQueuesToMonitor() {
            return scheduledReconnectionHandler.getQueuesToMonitor();
        }
    }

    private static final ScheduledReconnectionHandlerConfiguration SCHEDULED_RECONNECTION_HANDLER_CONFIGURATION =
        new ScheduledReconnectionHandlerConfiguration(true, Duration.ofSeconds(2));

    private static final Function<SabreDavExtension, Module> TEST_RABBITMQ_MODULE = extension ->
        Modules.override(new TwakeCalendarRabbitMQModule())
            .with(binder -> {
                Multibinder.newSetBinder(binder, GuiceProbe.class).addBinding().to(ScheduledReconnectionHandlerProbe.class);
                binder.bind(ScheduledReconnectionHandlerConfiguration.class).toInstance(SCHEDULED_RECONNECTION_HANDLER_CONFIGURATION);
                binder.bind(RabbitMQConfiguration.class).toInstance(extension.dockerSabreDavSetup().rabbitMQConfiguration());
            });

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
        TEST_RABBITMQ_MODULE.apply(sabreDavExtension),
        binder -> {
            binder.bind(DavConfiguration.class).toInstance(sabreDavExtension.dockerSabreDavSetup().davConfiguration());
            binder.bind(MongoDBConfiguration.class).toInstance(sabreDavExtension.dockerSabreDavSetup().mongoDBConfiguration());
        });

    @Test
    void shouldRestartDavCalendarEventConsumerWhenConsumerDisconnected(TwakeCalendarGuiceServer server) {
        RabbitMQManagementAPI rabbitMQManagementAPI = RabbitMQManagementAPI.from(sabreDavExtension.dockerSabreDavSetup().rabbitMQConfiguration());

        awaitAtMost
            .untilAsserted(() -> assertThat(rabbitMQManagementAPI.queueDetails("/", "tcalendar:event:created")
                .consumerDetails()).hasSize(1));

        String consumerNameTag = rabbitMQManagementAPI.queueDetails("/", "tcalendar:event:created").consumerDetails().getFirst().tag();

        // try to close the consumer
        System.out.println("close consumer");
        server.getProbe(ScheduledReconnectionHandlerProbe.class).davCalendarEventConsumer.close();

        // then ReconnectionHandler will restart the consumer
        awaitAtMost
            .untilAsserted(() -> {
                List<RabbitMQManagementAPI.ConsumerDetails> consumerDetails = rabbitMQManagementAPI.queueDetails("/", "tcalendar:event:created")
                    .consumerDetails();
                assertThat(consumerDetails).hasSize(1);
                assertThat(consumerDetails.getFirst().tag()).isNotEqualTo(consumerNameTag);
            } );
    }

    @Test
    void shouldMonitorDavCalendarEventQueues(TwakeCalendarGuiceServer server) {
        assertThat(server.getProbe(ScheduledReconnectionHandlerProbe.class).getQueuesToMonitor())
            .contains("tcalendar:event:created",
                "tcalendar:event:updated",
                "tcalendar:event:deleted",
                "tcalendar:event:cancel",
                "tcalendar:event:request");
    }
}