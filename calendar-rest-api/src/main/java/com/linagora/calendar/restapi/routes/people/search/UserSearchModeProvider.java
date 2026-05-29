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

import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.core.Domain;

import com.linagora.calendar.storage.UserSearchMode;

import reactor.core.publisher.Mono;

public class UserSearchModeProvider {

    private final Set<Domain> userSearchDisabledDomains;
    private final Set<Domain> userSearchLimitedDomains;

    @Inject
    public UserSearchModeProvider(@Named("userSearchDisabledDomains") Set<Domain> userSearchDisabledDomains,
                                  @Named("userSearchLimitedDomains") Set<Domain> userSearchLimitedDomains) {
        this.userSearchDisabledDomains = userSearchDisabledDomains;
        this.userSearchLimitedDomains = userSearchLimitedDomains;
    }

    public Mono<UserSearchMode> resolveUserSearchMode(Domain domain) {
        if (userSearchDisabledDomains.contains(domain)) {
            return Mono.just(UserSearchMode.DISABLED);
        }
        if (userSearchLimitedDomains.contains(domain)) {
            return Mono.just(UserSearchMode.LIMITED);
        }
        return Mono.just(UserSearchMode.ENABLED);
    }
}
