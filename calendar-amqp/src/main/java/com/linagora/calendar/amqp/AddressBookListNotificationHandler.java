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

import jakarta.inject.Inject;

import org.apache.james.events.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.amqp.AddressBookListNotificationConsumer.AddressBookListChangesMessage;
import com.linagora.calendar.amqp.AddressBookListNotificationConsumer.AddressBookListExchange;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.AddressBookListChangedEvent;
import com.linagora.calendar.storage.AddressBookListChangedEvent.ChangeType;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.UsernameRegistrationKey;

import reactor.core.publisher.Mono;

public class AddressBookListNotificationHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AddressBookListNotificationHandler.class);
    private static final String SUBSCRIPTION_TYPE_PROPERTY = "openpaas:subscription-type";
    private static final String DELEGATION_SUBSCRIPTION_TYPE = "delegation";
    private static final String PUBLIC_SUBSCRIPTION_TYPE = "public";

    private final EventBus eventBus;
    private final OpenPaaSUserDAO userDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final CardDavClient cardDavClient;

    @Inject
    public AddressBookListNotificationHandler(EventBus eventBus, OpenPaaSUserDAO userDAO,
                                              OpenPaaSDomainDAO domainDAO, CardDavClient cardDavClient) {
        this.eventBus = eventBus;
        this.userDAO = userDAO;
        this.domainDAO = domainDAO;
        this.cardDavClient = cardDavClient;
    }

    public Mono<Void> handle(AddressBookListExchange exchange, AddressBookListChangesMessage message) {
        AddressBookURL addressBookURL = message.addressBookURL();
        // The path identifies the list owner; connectedUser is only the actor (possibly an admin delegate).
        return userDAO.retrieve(addressBookURL.baseId())
            .switchIfEmpty(Mono.defer(() -> ignoreNonUserOwner(exchange, addressBookURL)))
            .flatMap(user -> resolveChangeType(exchange, addressBookURL, user)
                .flatMap(changeType -> eventBus.dispatch(
                    AddressBookListChangedEvent.of(user.username(), addressBookURL, changeType),
                    new UsernameRegistrationKey(user.username()))))
            .then();
    }

    private Mono<ChangeType> resolveChangeType(AddressBookListExchange exchange, AddressBookURL addressBookURL, OpenPaaSUser user) {
        return switch (exchange) {
            case ADDRESS_BOOK_CREATED -> Mono.just(ChangeType.CREATED);
            case ADDRESS_BOOK_UPDATED, SUBSCRIPTION_UPDATED -> Mono.just(ChangeType.UPDATED);
            case ADDRESS_BOOK_DELETED, SUBSCRIPTION_DELETED -> Mono.just(ChangeType.DELETED);
            case SUBSCRIPTION_CREATED -> cardDavClient.fetchAddressBookMetadata(user.username(), addressBookURL)
                .flatMap(metadata -> switch (metadata.path(SUBSCRIPTION_TYPE_PROPERTY).asText()) {
                    case DELEGATION_SUBSCRIPTION_TYPE -> Mono.just(ChangeType.DELEGATED);
                    case PUBLIC_SUBSCRIPTION_TYPE -> Mono.just(ChangeType.SUBSCRIBED);
                    default -> Mono.error(new IllegalArgumentException("Unknown subscription type for " + addressBookURL.asUri()));
                });
        };
    }

    private Mono<OpenPaaSUser> ignoreNonUserOwner(AddressBookListExchange exchange, AddressBookURL addressBookURL) {
        return domainDAO.retrieve(addressBookURL.baseId()).hasElement()
            .flatMap(isDomain -> {
                // Domain books (including domain-members) are not a user's personal address book list.
                if (isDomain) {
                    LOGGER.debug("Ignore address book list notification for domain book {}", addressBookURL.asUri());
                    return Mono.empty();
                }
                // The owner may be deleted before queued book or subscription deletions are consumed.
                if (exchange == AddressBookListExchange.ADDRESS_BOOK_DELETED || exchange == AddressBookListExchange.SUBSCRIPTION_DELETED) {
                    LOGGER.debug("Ignore deleted address book list notification for unknown owner {}", addressBookURL.asUri());
                    return Mono.empty();
                }
                return Mono.error(new IllegalStateException("Cannot resolve address book list owner " + addressBookURL.baseId().value()));
            });
    }
}
