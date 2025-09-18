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

import static com.linagora.calendar.restapi.routes.people.search.ContactSearchProvider.buildAvatarUrl;

import java.net.URL;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.james.core.Username;

import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.restapi.routes.PeopleSearchRoute;
import com.linagora.calendar.restapi.routes.PeopleSearchRoute.ObjectType;
import com.linagora.calendar.restapi.routes.people.search.ContactSearchProvider.ContactResponseDTO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class UserSearchProvider implements PeopleSearchProvider {

    public static final ImmutableSet<ObjectType> OBJECT_TYPES = ImmutableSet.of(ObjectType.USER);

    private final OpenPaaSUserDAO userDAO;
    private final URL baseAvatarUrl;

    @Inject
    public UserSearchProvider(OpenPaaSUserDAO userDAO,
                              @Named("selfUrl") URL baseAvatarUrl) {
        this.userDAO = userDAO;
        this.baseAvatarUrl = baseAvatarUrl;
    }

    @Override
    public Set<PeopleSearchRoute.ObjectType> supportedTypes() {
        return OBJECT_TYPES;
    }

    @Override
    public Flux<PeopleSearchRoute.ResponseDTO> search(Username username, String query, Set<ObjectType> objectTypesFilter, int limit) {
        if (CollectionUtils.isEmpty(objectTypesFilter) || objectTypesFilter.contains(ObjectType.CONTACT)) {
            return Flux.empty(); // handled by ContactSearchProvider
        }

        return Mono.justOrEmpty(username.getDomainPart())
            .flatMapMany(domain -> userDAO.search(domain, query, limit))
            .map(this::toResponseDTO);
    }

    private ContactResponseDTO toResponseDTO(OpenPaaSUser user) {
        return new ContactResponseDTO(user.id().value(),
            user.username().asString(),
            user.fullName(),
            buildAvatarUrl(baseAvatarUrl, user.username().asString()).toString(),
            ObjectType.USER.name().toLowerCase());
    }
}
