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

import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.core.Username;

import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.model.ResourceId;

import reactor.core.publisher.Mono;

public class LocalRecipientResolver {

    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final ResourceDAO resourceDAO;
    private final OpenPaaSDomainDAO domainDAO;

    @Inject
    @Singleton
    public LocalRecipientResolver(OpenPaaSUserDAO openPaaSUserDAO,
                                  ResourceDAO resourceDAO,
                                  OpenPaaSDomainDAO domainDAO) {
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.resourceDAO = resourceDAO;
        this.domainDAO = domainDAO;
    }

    public Mono<Optional<OpenPaaSId>> resolve(Username username) {
        return resolveAsUser(username)
            .switchIfEmpty(resolveAsResource(username))
            .switchIfEmpty(Mono.just(Optional.empty()));
    }

    private Mono<Optional<OpenPaaSId>> resolveAsUser(Username username) {
        return openPaaSUserDAO.retrieve(username)
            .map(OpenPaaSUser::id)
            .map(Optional::of);
    }

    private Mono<Optional<OpenPaaSId>> resolveAsResource(Username username) {
        ResourceId resourceId = new ResourceId(username.getLocalPart());
        return Mono.justOrEmpty(username.getDomainPart())
            .flatMap(domainDAO::retrieve)
            .flatMap(domain -> resourceDAO.exist(resourceId, domain.id()))
            .filter(exist -> exist)
            .map(ignored -> Optional.of(resourceId.asOpenPaaSId()));
    }
}