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

import java.util.Arrays;
import java.util.List;

import org.apache.commons.configuration2.Configuration;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;

import com.google.common.base.Preconditions;

public record TwakeSpaceConfiguration(RabbitMQConfiguration rabbitMQ,
                                      String exchange,
                                      List<String> routingKeys,
                                      String queue,
                                      String deadLetterQueue,
                                      String activityExchange,
                                      RabbitMQConfiguration calendarRabbitMQ,
                                      String calendarQueue,
                                      String calendarDeadLetterQueue) {
    private static final String RABBITMQ_PREFIX = "twakespace.rabbitmq";
    private static final String RABBITMQ_URI = RABBITMQ_PREFIX + ".uri";
    private static final String EXCHANGE = "twakespace.exchange";
    private static final String ROUTING_KEYS = "twakespace.routing.keys";
    private static final String QUEUE = "twakespace.queue";
    private static final String DEAD_LETTER_QUEUE = "twakespace.dead.letter.queue";
    private static final String ACTIVITY_EXCHANGE = "twakespace.activity.exchange";
    private static final String CALENDAR_QUEUE = "twakespace.calendar.queue";
    private static final String CALENDAR_DEAD_LETTER_QUEUE = "twakespace.calendar.dead.letter.queue";
    private static final String DEFAULT_EXCHANGE = "space";
    private static final List<String> DEFAULT_ROUTING_KEYS = Arrays.stream(SpaceEventType.values())
        .map(SpaceEventType::routingKey)
        .toList();
    private static final String DEFAULT_QUEUE = "tcalendar:twake-space";
    private static final String DEFAULT_DEAD_LETTER_QUEUE = "tcalendar:twake-space-dead-letter";
    private static final String DEFAULT_ACTIVITY_EXCHANGE = "activity";
    private static final String DEFAULT_CALENDAR_QUEUE = "tcalendar:twake-space-calendar";
    private static final String DEFAULT_CALENDAR_DEAD_LETTER_QUEUE = "tcalendar:twake-space-calendar-dead-letter";

    public static TwakeSpaceConfiguration from(Configuration extensions, RabbitMQConfiguration sideServiceRabbitMQ) {
        List<String> routingKeys = extensions.getList(String.class, ROUTING_KEYS, DEFAULT_ROUTING_KEYS);
        Preconditions.checkArgument(!routingKeys.isEmpty() && routingKeys.stream().noneMatch(String::isBlank), "'%s' can not be blank", ROUTING_KEYS);

        RabbitMQConfiguration rabbitMQ = extensions.containsKey(RABBITMQ_URI)
            ? RabbitMQConfiguration.from(extensions.subset(RABBITMQ_PREFIX))
            : sideServiceRabbitMQ;

        return new TwakeSpaceConfiguration(rabbitMQ,
            name(extensions, EXCHANGE, DEFAULT_EXCHANGE),
            routingKeys,
            name(extensions, QUEUE, DEFAULT_QUEUE),
            name(extensions, DEAD_LETTER_QUEUE, DEFAULT_DEAD_LETTER_QUEUE),
            name(extensions, ACTIVITY_EXCHANGE, DEFAULT_ACTIVITY_EXCHANGE),
            sideServiceRabbitMQ,
            name(extensions, CALENDAR_QUEUE, DEFAULT_CALENDAR_QUEUE),
            name(extensions, CALENDAR_DEAD_LETTER_QUEUE, DEFAULT_CALENDAR_DEAD_LETTER_QUEUE));
    }

    private static String name(Configuration extensions, String key, String defaultValue) {
        String value = extensions.getString(key, defaultValue);
        Preconditions.checkArgument(!value.isBlank(), "'%s' can not be blank", key);
        return value;
    }
}
