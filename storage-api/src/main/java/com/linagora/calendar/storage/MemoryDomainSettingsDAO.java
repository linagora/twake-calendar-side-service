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

package com.linagora.calendar.storage;

import static org.apache.james.util.ReactorUtils.publishIfPresent;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.james.core.Domain;

import reactor.core.publisher.Mono;

public class MemoryDomainSettingsDAO implements DomainSettingsDAO {

    private final Map<Domain, DomainSettings> store = new ConcurrentHashMap<>();

    @Override
    public Mono<DomainSettings> retrieve(Domain domain) {
        return Mono.fromCallable(() -> Optional.ofNullable(store.get(domain)))
            .handle(publishIfPresent());
    }

    @Override
    public Mono<Void> save(Domain domain, DomainSettings settings) {
        return Mono.fromRunnable(() -> store.put(domain, settings));
    }

    @Override
    public Mono<Void> patch(Domain domain, DomainSettingsPatch patch) {
        return retrieve(domain)
            .defaultIfEmpty(DomainSettings.DEFAULT_DOMAIN_SETTINGS)
            .map(existing -> {
                DomainSettings.Builder builder = DomainSettings.builder();
                patch.userSearchMode().notKeptOrElse(existing.userSearchMode()).ifPresent(builder::userSearchMode);
                patch.resourceSearchEnabled().notKeptOrElse(existing.resourceSearchEnabled()).ifPresent(builder::resourceSearchEnabled);
                patch.defaultCalendarPublicVisibility().notKeptOrElse(existing.defaultCalendarPublicVisibility()).ifPresent(builder::defaultCalendarPublicVisibility);
                return builder.build();
            }).flatMap(merged -> save(domain, merged));
    }
}
