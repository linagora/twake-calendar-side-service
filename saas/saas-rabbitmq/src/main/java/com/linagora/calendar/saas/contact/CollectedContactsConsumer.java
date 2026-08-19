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

package com.linagora.calendar.saas.contact;

import static com.linagora.tmail.saas.rabbitmq.TWPConstants.TWP_INJECTION_KEY;
import static org.apache.james.backends.rabbitmq.Constants.DURABLE;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.io.Closeable;
import java.util.Optional;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.core.Username;
import org.apache.james.lifecycle.api.Startable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.tmail.saas.rabbitmq.TWPCommonRabbitMQConfiguration;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.Sender;

public class CollectedContactsConsumer implements Closeable, Startable {
    public static final String EXCHANGE = "twake:contacts:collected";
    public static final String QUEUE = "tcalendar:contacts:collected";
    public static final String DEAD_LETTER_QUEUE = "tcalendar:contacts:collected-dead-letter";

    private static final Logger LOGGER = LoggerFactory.getLogger(CollectedContactsConsumer.class);
    private static final String COLLECTED_ADDRESS_BOOK = "collected";

    private final ReceiverProvider receiverProvider;
    private final Sender sender;
    private final RabbitMQConfiguration rabbitMQConfiguration;
    private final TWPCommonRabbitMQConfiguration twpCommonRabbitMQConfiguration;
    private final OpenPaaSUserDAO userDAO;
    private final CardDavClient cardDavClient;
    private final CollectedContactConverter contactConverter;
    private Disposable consumer;

    @Inject
    public CollectedContactsConsumer(@Named(TWP_INJECTION_KEY) ReactorRabbitMQChannelPool channelPool,
                                     @Named(TWP_INJECTION_KEY) RabbitMQConfiguration rabbitMQConfiguration,
                                     TWPCommonRabbitMQConfiguration twpCommonRabbitMQConfiguration,
                                     OpenPaaSUserDAO userDAO,
                                     CardDavClient cardDavClient,
                                     CollectedContactConverter contactConverter) {
        receiverProvider = channelPool::createReceiver;
        sender = channelPool.getSender();
        this.rabbitMQConfiguration = rabbitMQConfiguration;
        this.twpCommonRabbitMQConfiguration = twpCommonRabbitMQConfiguration;
        this.userDAO = userDAO;
        this.cardDavClient = cardDavClient;
        this.contactConverter = contactConverter;
    }

    public void init() {
        Flux.concat(
                sender.declareExchange(ExchangeSpecification.exchange(EXCHANGE)
                    .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())),
                sender.declareExchange(ExchangeSpecification.exchange(DEAD_LETTER_QUEUE)
                    .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())),
                sender.declareQueue(QueueSpecification.queue(DEAD_LETTER_QUEUE)
                    .durable(DURABLE).arguments(queueArguments().build())),
                sender.bind(BindingSpecification.binding().exchange(DEAD_LETTER_QUEUE)
                    .queue(DEAD_LETTER_QUEUE).routingKey(EMPTY_ROUTING_KEY)),
                sender.declareQueue(QueueSpecification.queue(QUEUE)
                    .durable(DURABLE).arguments(queueArguments()
                        .deadLetter(DEAD_LETTER_QUEUE)
                        .build())),
                sender.bind(BindingSpecification.binding().exchange(EXCHANGE)
                    .queue(QUEUE).routingKey(EMPTY_ROUTING_KEY)))
            .then()
            .block();
        start();
    }

    private QueueArguments.Builder queueArguments() {
        if (twpCommonRabbitMQConfiguration.quorumQueuesBypass()) {
            return QueueArguments.builder();
        }
        return rabbitMQConfiguration.workQueueArgumentsBuilder();
    }

    public void start() {
        consumer = Flux.using(receiverProvider::createReceiver,
                receiver -> receiver.consumeManualAck(QUEUE, new ConsumeOptions().qos(DEFAULT_CONCURRENCY)),
                Receiver::close)
            .concatMap(this::handleDelivery)
            .subscribe();
    }

    public void restart() {
        close();
        start();
    }

    @Override
    @PreDestroy
    public void close() {
        Optional.ofNullable(consumer).ifPresent(Disposable::dispose);
    }

    private Mono<Void> handleDelivery(AcknowledgableDelivery delivery) {
        return Mono.fromCallable(() -> CollectedContactsDTO.deserialize(delivery.getBody()))
            .flatMap(this::handle)
            .doOnSuccess(_ -> delivery.ack())
            .onErrorResume(error -> {
                LOGGER.error("Error when consuming collected contacts message", error);
                delivery.nack(false);
                return Mono.empty();
            });
    }

    Mono<Void> handle(CollectedContactsDTO collectedContacts) {
        return userDAO.retrieve(Username.of(collectedContacts.userEmail()))
            .flatMap(user -> {
                AddressBookURL addressBook = new AddressBookURL(user.id(), COLLECTED_ADDRESS_BOOK);
                return Flux.fromIterable(collectedContacts.collectedContacts())
                    .map(contactConverter::convert)
                    .concatMap(contact -> cardDavClient.upsertContact(user.username(), addressBook, contact.uid().value(), contact.vcard()))
                    .then();
            });
    }
}
