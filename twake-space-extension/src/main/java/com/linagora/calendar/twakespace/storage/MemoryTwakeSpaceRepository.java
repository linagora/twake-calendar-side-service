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

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;

import com.linagora.calendar.twakespace.model.OrganizationId;
import com.linagora.calendar.twakespace.model.SpaceId;

import reactor.core.publisher.Mono;

public class MemoryTwakeSpaceRepository implements TwakeSpaceRepository {
    private final Map<SpaceId, TwakeSpace> spaces = new ConcurrentHashMap<>();
    private final Clock clock;

    @Inject
    public MemoryTwakeSpaceRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<Void> save(SpaceId spaceId, OrganizationId organization, Domain domain) {
        return Mono.fromRunnable(() -> spaces.put(spaceId, new TwakeSpace(spaceId, Optional.of(organization), domain, Optional.empty())));
    }

    @Override
    public Mono<Void> markDeleted(SpaceId spaceId) {
        return Mono.fromRunnable(() -> spaces.computeIfPresent(spaceId, (id, space) -> space.deletion().isPresent()
            ? space
            : new TwakeSpace(id, space.organization(), space.domain(), Optional.of(clock.instant()))));
    }

    @Override
    public Mono<TwakeSpace> retrieve(SpaceId spaceId) {
        return Mono.justOrEmpty(spaces.get(spaceId));
    }
}
