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

package com.linagora.calendar.webadmin.service;

import org.apache.james.core.MailAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.OpenPaaSId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface DavDomainMemberUpdateApplied {

    Mono<Void> apply(DomainMemberUpdate update);

    class Default implements DavDomainMemberUpdateApplied {
        private static final Logger LOGGER = LoggerFactory.getLogger(Default.class);

        private final CardDavClient davClient;
        private final OpenPaaSId domainId;

        public Default(CardDavClient davClient,
                       OpenPaaSId domainId) {
            this.davClient = davClient;
            this.domainId = domainId;
        }

        @Override
        public Mono<Void> apply(DomainMemberUpdate memberUpdate) {
            return performRemovedMembers(memberUpdate)
                .then(performUpdatedMembers(memberUpdate))
                .then(performAddedMembers(memberUpdate));
        }

        private Mono<Void> performAddedMembers(DomainMemberUpdate update) {
            return Flux.fromIterable(update.added())
                .flatMap(contact -> davClient.upsertContactDomainMembers(domainId, contact.vcardUid(), contact.toVcardBytes())
                    .onErrorContinue((e, object)
                        -> LOGGER.error("Failed to add contact has mail {} to domain {}: {}", contact.mail().map(MailAddress::asString).orElse(null), domainId.value(), e.getMessage())))
                .then();
        }

        private Mono<Void> performRemovedMembers(DomainMemberUpdate update) {
            return Flux.fromIterable(update.deleted())
                .flatMap(contact -> davClient.deleteContactDomainMembers(domainId, contact.vcardUid())
                    .onErrorContinue((e, object)
                        -> LOGGER.error("Failed to delete contact has mail {} to domain {}: {}", contact.mail().map(MailAddress::asString).orElse(null), domainId.value(), e.getMessage())))
                .then();
        }

        private Mono<Void> performUpdatedMembers(DomainMemberUpdate update) {
            return Flux.fromIterable(update.updated())
                .flatMap(contact -> davClient.upsertContactDomainMembers(domainId, contact.vcardUid(), contact.toVcardBytes())
                    .onErrorContinue((e, object)
                        -> LOGGER.error("Failed to update contact has mail {} to domain {}: {}", contact.mail().map(MailAddress::asString).orElse(null), domainId.value(), e.getMessage())))
                .then();
        }
    }
}
