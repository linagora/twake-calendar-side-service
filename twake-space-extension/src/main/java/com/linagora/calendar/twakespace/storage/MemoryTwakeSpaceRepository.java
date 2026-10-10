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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.linagora.calendar.twakespace.model.SpaceId;
import com.linagora.calendar.twakespace.model.TwakeSpace;

import reactor.core.publisher.Mono;

public class MemoryTwakeSpaceRepository implements TwakeSpaceRepository {
    private final Map<SpaceId, TwakeSpace> spaces = new ConcurrentHashMap<>();

    @Override
    public Mono<TwakeSpace> merge(TwakeSpace change) {
        return Mono.fromCallable(() -> spaces.merge(change.id(), change, TwakeSpace::merge));
    }

    @Override
    public Mono<TwakeSpace> retrieve(SpaceId spaceId) {
        return Mono.justOrEmpty(spaces.get(spaceId));
    }
}
