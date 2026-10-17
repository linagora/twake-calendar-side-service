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
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.lifecycle.api.Startable;

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

public class AddressBookDelegatedNotificationConsumer implements Closeable, Startable {
    public static final String QUEUE = "tcalendar:addressbook:delegated:created";
    public static final String DEAD_LETTER_QUEUE = QUEUE + ":dead-letter";
    private static final String EXCHANGE = "sabre:addressbook:subscription:created";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ManagedRabbitMQConsumer consumer;
    private final DelegatedAddressBookNotificationHandler notificationHandler;

    @Inject
    public AddressBookDelegatedNotificationConsumer(ReactorRabbitMQChannelPool channelPool,
                                                    @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                                    DelegatedAddressBookNotificationHandler notificationHandler) {
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

    public void restart() {
        consumer.restart();
    }

    @Override
    @PreDestroy
    public void close() {
        consumer.close();
    }

    private Mono<Void> handleMessage(AcknowledgableDelivery delivery) {
        return Mono.fromCallable(() -> AddressBookDelegatedCreatedMessage.deserialize(delivery.getBody()))
            .flatMap(notificationHandler::handle);
    }

    public record AddressBookDelegatedCreatedMessage(@JsonProperty("path") String path,
                                                     @JsonProperty("connectedUser") String connectedUser) {
        public static AddressBookDelegatedCreatedMessage deserialize(byte[] payload) {
            try {
                return OBJECT_MAPPER.readValue(payload, AddressBookDelegatedCreatedMessage.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to deserialize address book delegation message: "
                    + new String(payload, StandardCharsets.UTF_8), e);
            }
        }

        public AddressBookURL addressBookURL() {
            return AddressBookURL.parse(path);
        }
    }
}
