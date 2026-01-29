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

package com.linagora.calendar.saas;

import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSMessageHandler;

import reactor.core.publisher.Mono;

public class SaaSUserSubscriptionHandler implements SaaSMessageHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SaaSUserSubscriptionHandler.class);

    private final OpenPaaSUserDAO userDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final CardDavClient cardDavClient;

    public SaaSUserSubscriptionHandler(OpenPaaSUserDAO userDAO,
                                       OpenPaaSDomainDAO domainDAO,
                                       CardDavClient cardDavClient) {
        this.userDAO = userDAO;
        this.domainDAO = domainDAO;
        this.cardDavClient = cardDavClient;
    }

    @Override
    public Mono<Void> handleMessage(byte[] message) {
        return Mono.fromCallable(() -> SaaSCalendarSubscriptionDeserializer.parseUserMessage(message))
            .filter(UserSubscriptionMessage::hasCalendarFeature)
            .flatMap(userMessage -> registerUser(userMessage.username())
                .flatMap(this::addUserToDomainAddressBook))
            .then();
    }

    private Mono<OpenPaaSUser> registerUser(Username username) {
        return userDAO.retrieve(username)
            .doOnNext(existingUser -> LOGGER.debug("User {} already exists, skipping registration", username.asString()))
            .switchIfEmpty(userDAO.add(username)
                .doOnSuccess(created -> LOGGER.info("Registered user {} from SaaS subscription", username.asString())));
    }

    private Mono<Void> addUserToDomainAddressBook(OpenPaaSUser user) {
        return Mono.justOrEmpty(user.username().getDomainPart())
            .flatMap(domain -> domainDAO.retrieve(domain)
                .flatMap(openPaaSDomain -> upsertUserContact(openPaaSDomain, user)));
    }

    private Mono<Void> upsertUserContact(OpenPaaSDomain domain, OpenPaaSUser user) {
        return Mono.fromSupplier(Throwing.supplier(() -> AddressBookContact.builder().mail(user.username().asMailAddress()).build()))
            .flatMap(contact -> cardDavClient.upsertContactDomainMembers(domain.id(), contact.vcardUid(), contact.toVcardBytes()))
            .doOnSuccess(ignored -> LOGGER.info("Added user {} to domain {} addressbook",
                user.username().asString(), domain.domain().asString()));
    }
}
