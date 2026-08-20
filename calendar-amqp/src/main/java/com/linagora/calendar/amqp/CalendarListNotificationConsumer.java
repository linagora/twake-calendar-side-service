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

package com.linagora.calendar.amqp;

import static com.linagora.calendar.amqp.CalendarAmqpModule.INJECT_KEY_DAV;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.lifecycle.api.Startable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.inject.name.Named;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.tmail.rabbitmq.ManagedRabbitMQConsumer;
import com.linagora.tmail.rabbitmq.QueueDeclaration;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;

public class CalendarListNotificationConsumer implements Closeable, Startable {

    public static final String QUEUE_NAME = "tcalendar:calendar:list:notification";
    public static final String DEAD_LETTER_QUEUE = QUEUE_NAME + ":dead-letter";

    public enum CalendarListExchange {
        SUBSCRIPTION_CREATED("calendar:subscription:created"),
        SUBSCRIPTION_UPDATED("calendar:subscription:updated"),
        SUBSCRIPTION_DELETED("calendar:subscription:deleted"),
        CALENDAR_CREATED("calendar:calendar:created"),
        CALENDAR_UPDATED("calendar:calendar:updated"),
        CALENDAR_DELETED("calendar:calendar:deleted");

        private final String exchangeName;

        CalendarListExchange(String exchangeName) {
            this.exchangeName = exchangeName;
        }

        public String asString() {
            return exchangeName;
        }

        public static Optional<CalendarListExchange> fromString(String exchangeName) {
            return Stream.of(values())
                .filter(exchange -> exchange.exchangeName.equals(exchangeName))
                .findFirst();
        }
    }

    private static final List<CalendarListExchange> EXCHANGES = List.of(CalendarListExchange.values());

    private final ManagedRabbitMQConsumer consumer;
    private final CalendarListNotificationHandler notificationHandler;

    @Inject
    public CalendarListNotificationConsumer(ReactorRabbitMQChannelPool channelPool,
                                            @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                            CalendarListNotificationHandler notificationHandler) {
        this.notificationHandler = notificationHandler;
        QueueDeclaration.Builder queueDeclaration = QueueDeclaration.builder()
            .queue(QUEUE_NAME)
            .deadLetterQueue(DEAD_LETTER_QUEUE);
        EXCHANGES.forEach(exchange -> queueDeclaration.binding(exchange.asString(), BuiltinExchangeType.FANOUT, EMPTY_ROUTING_KEY));
        this.consumer = new ManagedRabbitMQConsumer.Factory(channelPool)
            .create(ManagedRabbitMQConsumer.Parameters.builder()
                .queueDeclaration(queueDeclaration.build())
                .queueArguments(queueArgumentSupplier)
                .qos(DEFAULT_CONCURRENCY)
                .concurrency(DEFAULT_CONCURRENCY)
                .handleDelivery(this::messageConsume)
                .build());
    }

    public void init() {
        consumer.init();
    }

    public void restart() {
        consumer.restart();
    }

    @Override
    @PreDestroy
    public void close() {
        consumer.close();
    }

    private Mono<Void> messageConsume(AcknowledgableDelivery ackDelivery) {
        String exchangeName = ackDelivery.getEnvelope().getExchange();
        CalendarListExchange exchange = CalendarListExchange.fromString(exchangeName)
            .orElseThrow(() -> new IllegalArgumentException("Unsupported exchange name: " + exchangeName));

        return Mono.fromCallable(() -> CalendarListChangesMessage.deserialize(ackDelivery.getBody()))
            .flatMap(message -> notificationHandler.handle(exchange, message).then());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CalendarListChangesMessage(@JsonProperty("calendarPath") String calendarPath,
                                             @JsonProperty("calendarProps") Map<String, JsonNode> calendarProps) {

        public static CalendarListChangesMessage deserialize(byte[] json) {
            try {
                return OBJECT_MAPPER.readValue(json, CalendarListChangesMessage.class);
            } catch (IOException e) {
                throw new RuntimeException("Failed to deserialize calendarListChangesMessage: " + new String(json, StandardCharsets.UTF_8), e);
            }
        }

        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        private static final String ACCESS_KEY = "access";

        public CalendarURL calendarURL() {
            return CalendarURL.parse(calendarPath);
        }

        public Optional<String> access() {
            return Optional.ofNullable(calendarProps)
                .map(props -> props.get(ACCESS_KEY))
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText);
        }
    }
}
