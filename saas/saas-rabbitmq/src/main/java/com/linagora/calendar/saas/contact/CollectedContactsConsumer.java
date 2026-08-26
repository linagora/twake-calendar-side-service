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

import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final CollectedContactUpdateCalculator contactUpdateCalculator;

    @Inject
    public CollectedContactsConsumer(@Named(TWP_INJECTION_KEY) ReactorRabbitMQChannelPool channelPool,
                                     @Named(TWP_INJECTION_KEY) RabbitMQConfiguration rabbitMQConfiguration,
                                     TWPCommonRabbitMQConfiguration twpCommonRabbitMQConfiguration,
                                     OpenPaaSUserDAO userDAO,
                                     CardDavClient cardDavClient,
                                     CollectedContactUpdateCalculator contactUpdateCalculator) {
        this.userDAO = userDAO;
        this.cardDavClient = cardDavClient;
        this.contactUpdateCalculator = contactUpdateCalculator;
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
                    .concatMap(contactData -> handleContact(user.username(), addressBook, contactData))
                    .then();
            });
    }

    private Mono<Void> handleContact(Username username, AddressBookURL addressBook, ObjectNode contactData) {
        return Mono.fromCallable(() -> CollectedContact.parse(contactData))
            .flatMap(incomingContact -> cardDavClient.retrieveContact(username, addressBook, incomingContact.uid())
                .singleOptional()
                .flatMap(maybeExistingVCard -> maybeExistingVCard
                    .map(CollectedContact::parse)
                    .map(existingContact -> updateExistingContact(username, addressBook, existingContact, incomingContact))
                    .orElseGet(() -> createNewContact(username, addressBook, incomingContact))));
    }

    private Mono<Void> updateExistingContact(Username username, AddressBookURL addressBook, CollectedContact existingContact, CollectedContact incomingContact) {
        return Mono.fromCallable(() -> contactUpdateCalculator.calculate(existingContact, incomingContact))
            .flatMap(Mono::justOrEmpty)
            .flatMap(vCard -> cardDavClient.upsertContact(username, addressBook, incomingContact.uid().value(), vCard));
    }

    private Mono<Void> createNewContact(Username username, AddressBookURL addressBook, CollectedContact incomingContact) {
        return cardDavClient.upsertContact(username, addressBook, incomingContact.uid().value(), incomingContact.toVCardAsBytes());
    }
}
