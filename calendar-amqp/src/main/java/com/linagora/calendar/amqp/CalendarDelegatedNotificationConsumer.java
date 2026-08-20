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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.lifecycle.api.Startable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.lambdas.Throwing;
import com.google.inject.name.Named;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.tmail.rabbitmq.ManagedRabbitMQConsumer;
import com.linagora.tmail.rabbitmq.QueueDeclaration;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;

public class CalendarDelegatedNotificationConsumer implements Closeable, Startable {

    public static final String QUEUE = "tcalendar:calendar:delegated:created";
    public static final String DEAD_LETTER_QUEUE = QUEUE + ":dead-letter";
    private static final String EXCHANGE = "calendar:calendar:created";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarDelegatedNotificationConsumer.class);

    private final ManagedRabbitMQConsumer consumer;
    private final DelegatedCalendarNotificationHandler notificationHandler;

    @Inject
    @Singleton
    public CalendarDelegatedNotificationConsumer(ReactorRabbitMQChannelPool channelPool,
                                                 @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                                 DelegatedCalendarNotificationHandler notificationHandler) {
        this.notificationHandler = notificationHandler;
        this.consumer = new ManagedRabbitMQConsumer.Factory(channelPool)
            .create(ManagedRabbitMQConsumer.Parameters.builder()
                .queueDeclaration(QueueDeclaration.builder()
                    .binding(EXCHANGE, BuiltinExchangeType.FANOUT, EMPTY_ROUTING_KEY)
                    .queue(QUEUE)
                    .deadLetterQueue(DEAD_LETTER_QUEUE)
                    .build())
                .queueArguments(queueArgumentSupplier)
                .qos(DEFAULT_CONCURRENCY)
                .concurrency(DEFAULT_CONCURRENCY)
                .handleDelivery(this::handleMessage)
                .build());
    }

    public void init() {
        consumer.init();
    }

    public void start() {
        consumer.start();
    }

    @Override
    @PreDestroy
    public void close() {
        LOGGER.info("Trying to stop delegated calendar notification consumer");
        consumer.close();
    }

    public void restart() {
        consumer.restart();
    }

    private Mono<Void> handleMessage(AcknowledgableDelivery acknowledgableDelivery) {
        return Mono.fromSupplier(Throwing.supplier(() -> CalendarDelegatedCreatedMessage.deserialize(acknowledgableDelivery.getBody())))
            .filter(hasDelegationRightKey())
            .flatMap(notificationHandler::handle);
    }

    private Predicate<CalendarDelegatedCreatedMessage> hasDelegationRightKey() {
        return message -> message.rightKey().isPresent();
    }

    public record CalendarDelegatedCreatedMessage(@JsonProperty("calendarPath") String calendarPath,
                                                  @JsonProperty("calendarProps") Map<String, JsonNode> calendarProps) {
        static final String RIGHT_KEY = "access";

        public static CalendarDelegatedCreatedMessage deserialize(byte[] payload) {
            try {
                return OBJECT_MAPPER.readValue(payload, CalendarDelegatedCreatedMessage.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize calendar delegated created message: " + new String(payload, StandardCharsets.UTF_8), e);
            }
        }

        public CalendarURL calendarURL() {
            return CalendarURL.parse(calendarPath);
        }

        public Optional<String> rightKey() {
            return Optional.ofNullable(calendarProps.get(RIGHT_KEY))
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText);
        }
    }
}