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

package com.linagora.calendar.dav;

import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.user.api.DeleteUserDataTaskStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.storage.OpenPaaSDomainDAO;

import reactor.core.publisher.Mono;

// Removes the member from the domain member address book of their domain, calendar user or not.
// Every card of the member email is deleted, as several writers may have added one.
public class DavDomainMemberDeletionTaskStep implements DeleteUserDataTaskStep {
    private static final Logger LOGGER = LoggerFactory.getLogger(DavDomainMemberDeletionTaskStep.class);

    private final CardDavClient cardDavClient;
    private final OpenPaaSDomainDAO domainDAO;

    @Inject
    public DavDomainMemberDeletionTaskStep(CardDavClient cardDavClient, OpenPaaSDomainDAO domainDAO) {
        this.cardDavClient = cardDavClient;
        this.domainDAO = domainDAO;
    }

    @Override
    public StepName name() {
        return new StepName("DavDomainMemberDeletionTaskStep");
    }

    @Override
    public int priority() {
        return 3;
    }

    @Override
    public Mono<Void> deleteUserData(Username username) {
        return Mono.justOrEmpty(username.getDomainPart())
            .flatMap(domainDAO::retrieve)
            .flatMap(domain -> Mono.fromCallable(username::asMailAddress)
                .flatMapMany(mail -> cardDavClient.findContactsDomainMembersByEmail(domain.id(), mail))
                .flatMap(card -> cardDavClient.deleteContactDomainMembers(domain.id(), card.vcardUid())
                    .doOnSuccess(ignored -> LOGGER.info("Deleted card {} of user {} from domain member addressbook of domain {}",
                        card.vcardUid(), username.asString(), domain.domain().asString())), DEFAULT_CONCURRENCY)
                .then());
    }
}
