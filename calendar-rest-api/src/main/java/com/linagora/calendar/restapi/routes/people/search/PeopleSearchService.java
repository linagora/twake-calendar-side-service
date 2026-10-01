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

package com.linagora.calendar.restapi.routes.people.search;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.james.mailbox.MailboxSession;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.linagora.calendar.restapi.routes.PeopleSearchRoute.ObjectType;
import com.linagora.calendar.restapi.routes.PeopleSearchRoute.ResponseDTO;

import reactor.core.publisher.Flux;

public class PeopleSearchService {
    private static final Map<String, Integer> OBJECT_TYPE_ORDER = ImmutableMap.of(
        ObjectType.USER.serialize(), 0,
        ObjectType.RESOURCE.serialize(), 1,
        ObjectType.CONTACT.serialize(), 2,
        ObjectType.TEAM_CALENDAR.serialize(), 3);

    private static final Comparator<ResponseDTO> RESULT_COMPARATOR =
        Comparator.<ResponseDTO>comparingInt(dto -> OBJECT_TYPE_ORDER.getOrDefault(dto.getObjectType(), Integer.MAX_VALUE))
            .thenComparing(ResponseDTO::getDisplayName, String.CASE_INSENSITIVE_ORDER);

    private final Set<PeopleSearchProvider> searchProviders;

    @Inject
    public PeopleSearchService(Set<PeopleSearchProvider> searchProviders) {
        this.searchProviders = searchProviders;
    }

    public Flux<ResponseDTO> search(MailboxSession session, String query, Set<ObjectType> objectTypesFilter, int limit) {
        return Flux.fromIterable(searchProviders)
            .filter(provider -> objectTypesFilter.isEmpty() || !Sets.intersection(
                objectTypesFilter,
                provider.supportedTypes()).isEmpty())
            .flatMap(provider -> provider.search(session, query, objectTypesFilter, limit))
            .collectSortedList(RESULT_COMPARATOR)
            .flatMapIterable(results -> results)
            .take(limit);
    }
}
