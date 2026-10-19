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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.name.Named;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.tmail.rabbitmq.ManagedRabbitMQConsumer;
import com.linagora.tmail.rabbitmq.QueueDeclaration;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;

public class AddressBookListNotificationConsumer implements Closeable, Startable {

    public static final String QUEUE_NAME = "tcalendar:addressbook:list:notification";
    public static final String DEAD_LETTER_QUEUE = QUEUE_NAME + ":dead-letter";

    public enum AddressBookListExchange {
        SUBSCRIPTION_CREATED("sabre:addressbook:subscription:created"),
        SUBSCRIPTION_UPDATED("sabre:addressbook:subscription:updated"),
        SUBSCRIPTION_DELETED("sabre:addressbook:subscription:deleted"),
        ADDRESS_BOOK_CREATED("sabre:addressbook:created"),
        ADDRESS_BOOK_UPDATED("sabre:addressbook:updated"),
        ADDRESS_BOOK_DELETED("sabre:addressbook:deleted");

        private final String exchangeName;

        AddressBookListExchange(String exchangeName) {
            this.exchangeName = exchangeName;
        }

        public String asString() {
            return exchangeName;
        }

        public static Optional<AddressBookListExchange> fromString(String exchangeName) {
            return Stream.of(values())
                .filter(exchange -> exchange.exchangeName.equals(exchangeName))
                .findFirst();
        }
    }

    private static final List<AddressBookListExchange> EXCHANGES = List.of(AddressBookListExchange.values());

    private final ManagedRabbitMQConsumer consumer;
    private final AddressBookListNotificationHandler notificationHandler;

    @Inject
    public AddressBookListNotificationConsumer(ReactorRabbitMQChannelPool channelPool,
                                            @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                            AddressBookListNotificationHandler notificationHandler) {
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
        AddressBookListExchange exchange = AddressBookListExchange.fromString(exchangeName)
            .orElseThrow(() -> new IllegalArgumentException("Unsupported exchange name: " + exchangeName));

        return Mono.fromCallable(() -> AddressBookListChangesMessage.deserialize(ackDelivery.getBody()))
            .flatMap(message -> notificationHandler.handle(exchange, message).then());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AddressBookListChangesMessage(@JsonProperty("path") String path) {
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        public static AddressBookListChangesMessage deserialize(byte[] json) {
            try {
                return OBJECT_MAPPER.readValue(json, AddressBookListChangesMessage.class);
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to deserialize address book list notification: "
                    + new String(json, StandardCharsets.UTF_8), e);
            }
        }

        public AddressBookURL addressBookURL() {
            return AddressBookURL.parse(path);
        }
    }
}
