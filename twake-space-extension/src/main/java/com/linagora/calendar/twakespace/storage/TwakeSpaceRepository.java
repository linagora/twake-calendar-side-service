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

package com.linagora.calendar.twakespace.storage;

import java.time.Instant;
import java.util.Optional;

import org.apache.james.core.Domain;

import com.linagora.calendar.twakespace.model.OrganizationId;
import com.linagora.calendar.twakespace.model.SpaceId;

import reactor.core.publisher.Mono;

public interface TwakeSpaceRepository {
    // Spaces recorded before the organization was stored have none.
    record TwakeSpace(SpaceId id, Optional<OrganizationId> organization, Domain domain, Optional<Instant> deletion) {
    }

    Mono<Void> save(SpaceId spaceId, OrganizationId organization, Domain domain);

    // A redelivered deleted keeps the first deletion time.
    Mono<Void> markDeleted(SpaceId spaceId);

    Mono<TwakeSpace> retrieve(SpaceId spaceId);
}
