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
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.io.Closeable;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.core.Username;
import org.apache.james.lifecycle.api.Startable;

import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.tmail.rabbitmq.ManagedRabbitMQConsumer;
import com.linagora.tmail.rabbitmq.QueueDeclaration;
import com.linagora.tmail.saas.rabbitmq.TWPCommonRabbitMQConfiguration;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;

public class CollectedContactsConsumer implements Closeable, Startable {
    public static final String EXCHANGE = "twake:contacts:collected";
    public static final String QUEUE = "tcalendar:contacts:collected";
    public static final String DEAD_LETTER_QUEUE = "tcalendar:contacts:collected-dead-letter";

    private static final String COLLECTED_ADDRESS_BOOK = "collected";

    private final ManagedRabbitMQConsumer consumer;
    private final OpenPaaSUserDAO userDAO;
    private final CardDavClient cardDavClient;
    private final CollectedContactConverter contactConverter;

    @Inject
    public CollectedContactsConsumer(@Named(TWP_INJECTION_KEY) ReactorRabbitMQChannelPool channelPool,
                                     @Named(TWP_INJECTION_KEY) RabbitMQConfiguration rabbitMQConfiguration,
                                     TWPCommonRabbitMQConfiguration twpCommonRabbitMQConfiguration,
                                     OpenPaaSUserDAO userDAO,
                                     CardDavClient cardDavClient,
                                     CollectedContactConverter contactConverter) {
        this.userDAO = userDAO;
        this.cardDavClient = cardDavClient;
        this.contactConverter = contactConverter;
        consumer = new ManagedRabbitMQConsumer.Factory(channelPool)
            .create(ManagedRabbitMQConsumer.Parameters.builder()
                .queueDeclaration(QueueDeclaration.builder()
                    .binding(EXCHANGE, BuiltinExchangeType.FANOUT, EMPTY_ROUTING_KEY)
                    .queue(QUEUE)
                    .deadLetterQueue(DEAD_LETTER_QUEUE)
                    .build())
                .queueArguments(() -> twpCommonRabbitMQConfiguration.quorumQueuesBypass()
                    ? QueueArguments.builder()
                    : rabbitMQConfiguration.workQueueArgumentsBuilder())
                .singleActiveConsumer()
                .qos(DEFAULT_CONCURRENCY)
                .handleDelivery(this::handleDelivery)
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

    private Mono<Void> handleDelivery(AcknowledgableDelivery delivery) {
        return Mono.fromCallable(() -> CollectedContactsDTO.deserialize(delivery.getBody()))
            .flatMap(this::handle);
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
