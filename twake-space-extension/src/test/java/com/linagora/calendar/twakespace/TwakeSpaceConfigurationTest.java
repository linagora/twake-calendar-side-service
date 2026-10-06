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

package com.linagora.calendar.twakespace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.net.URI;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TwakeSpaceConfigurationTest {
    private static final RabbitMQConfiguration SIDE_SERVICE_RABBITMQ = RabbitMQConfiguration.builder()
        .amqpUri(URI.create("amqp://calendar:secret@rabbitmq:5672/calendar"))
        .managementUri(URI.create("http://rabbitmq:15672"))
        .managementCredentials(new RabbitMQConfiguration.ManagementCredentials("calendar", "secret".toCharArray()))
        .build();
    private static final String NAMES = """
        twakespace.exchange=custom-space
        twakespace.routing.keys=twake.space.created, twake.space.member.#
        twakespace.queue=custom-queue
        twakespace.dead.letter.queue=custom-dead-letter
        twakespace.activity.exchange=custom-activity
        twakespace.calendar.queue=custom-calendar
        twakespace.calendar.dead.letter.queue=custom-calendar-dead-letter
        """;
    private static final String RABBITMQ = """
        twakespace.rabbitmq.uri=amqp://space:secret@space-rabbitmq:5672/%2F
        twakespace.rabbitmq.management.uri=http://space-rabbitmq:15672
        twakespace.rabbitmq.management.user=space
        twakespace.rabbitmq.management.password=secret
        twakespace.rabbitmq.quorum.queues.enable=true
        """;

    @Test
    void fromShouldDefaultTheNamesToTheTwakeSpaceOnes() throws Exception {
        TwakeSpaceConfiguration configuration = TwakeSpaceConfiguration.from(properties(""), SIDE_SERVICE_RABBITMQ);

        assertThat(configuration.exchange()).isEqualTo("space");
        assertThat(configuration.routingKeys()).containsExactly("twake.space.created", "twake.space.updated", "twake.space.deleted",
            "twake.space.member.added", "twake.space.member.role.changed", "twake.space.member.removed");
        assertThat(configuration.queue()).isEqualTo("tcalendar:twake-space");
        assertThat(configuration.deadLetterQueue()).isEqualTo("tcalendar:twake-space-dead-letter");
        assertThat(configuration.activityExchange()).isEqualTo("activity");
        assertThat(configuration.calendarQueue()).isEqualTo("tcalendar:twake-space-calendar");
        assertThat(configuration.calendarDeadLetterQueue()).isEqualTo("tcalendar:twake-space-calendar-dead-letter");
    }

    @Test
    void fromShouldConsumeTheCalendarEventsFromTheSideServiceRabbitMQ() throws Exception {
        assertThat(TwakeSpaceConfiguration.from(properties(RABBITMQ), SIDE_SERVICE_RABBITMQ).calendarRabbitMQ())
            .isSameAs(SIDE_SERVICE_RABBITMQ);
    }

    @Test
    void fromShouldReadTheConfiguredNames() throws Exception {
        TwakeSpaceConfiguration configuration = TwakeSpaceConfiguration.from(properties(NAMES), SIDE_SERVICE_RABBITMQ);

        assertThat(configuration.exchange()).isEqualTo("custom-space");
        assertThat(configuration.routingKeys()).containsExactly("twake.space.created", "twake.space.member.#");
        assertThat(configuration.queue()).isEqualTo("custom-queue");
        assertThat(configuration.deadLetterQueue()).isEqualTo("custom-dead-letter");
        assertThat(configuration.activityExchange()).isEqualTo("custom-activity");
        assertThat(configuration.calendarQueue()).isEqualTo("custom-calendar");
        assertThat(configuration.calendarDeadLetterQueue()).isEqualTo("custom-calendar-dead-letter");
    }

    @ParameterizedTest
    @ValueSource(strings = {"twakespace.exchange", "twakespace.routing.keys", "twakespace.queue", "twakespace.dead.letter.queue",
        "twakespace.activity.exchange", "twakespace.calendar.queue", "twakespace.calendar.dead.letter.queue"})
    void fromShouldFailWhenANameIsBlank(String key) throws Exception {
        PropertiesConfiguration configuration = properties(NAMES);
        configuration.setProperty(key, " ");

        assertThatThrownBy(() -> TwakeSpaceConfiguration.from(configuration, SIDE_SERVICE_RABBITMQ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(key);
    }

    @Test
    void fromShouldUseTheSideServiceRabbitMQByDefault() throws Exception {
        assertThat(TwakeSpaceConfiguration.from(properties(""), SIDE_SERVICE_RABBITMQ).rabbitMQ())
            .isSameAs(SIDE_SERVICE_RABBITMQ);
    }

    @Test
    void fromShouldReadTheRabbitMQPropertiesUnderTheTwakeSpacePrefix() throws Exception {
        RabbitMQConfiguration rabbitMQ = TwakeSpaceConfiguration.from(properties(RABBITMQ), SIDE_SERVICE_RABBITMQ).rabbitMQ();

        assertThat(rabbitMQ.getUri()).isEqualTo(URI.create("amqp://space:secret@space-rabbitmq:5672/%2F"));
        assertThat(rabbitMQ.isQuorumQueuesUsed()).isTrue();
    }

    private static PropertiesConfiguration properties(String content) throws Exception {
        PropertiesConfiguration configuration = new PropertiesConfiguration();
        configuration.setListDelimiterHandler(new DefaultListDelimiterHandler(','));
        configuration.read(new StringReader(content));
        return configuration;
    }
}
