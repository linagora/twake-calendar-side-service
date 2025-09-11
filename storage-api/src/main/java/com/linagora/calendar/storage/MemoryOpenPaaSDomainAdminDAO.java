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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MemoryOpenPaaSDomainAdminDAO implements OpenPaaSDomainAdminDAO {

    private final ConcurrentHashMap<OpenPaaSId, List<DomainAdministrator>> domainAdmins = new ConcurrentHashMap<>();

    @Override
    public Flux<DomainAdministrator> listAdmins(OpenPaaSId domainId) {
        return Flux.fromIterable(domainAdmins.getOrDefault(domainId, List.of()));
    }

    @Override
    public Mono<Void> revokeAdmin(OpenPaaSId domainId, OpenPaaSId userId) {
        return Mono.fromRunnable(() -> {
            List<DomainAdministrator> admins = domainAdmins.get(domainId);
            if (admins != null) {
                admins.removeIf(admin -> Objects.equals(admin.userId(), userId));
            }
        }).then();
    }

    @Override
    public Mono<Void> addAdmins(OpenPaaSId domainId, List<OpenPaaSId> userIdList) {
        return Mono.fromRunnable(() -> {
            List<DomainAdministrator> admins = domainAdmins.computeIfAbsent(domainId, id -> new ArrayList<>());

            for (OpenPaaSId userId : userIdList) {
                boolean exists = admins.stream()
                    .anyMatch(admin -> admin.userId().equals(userId));

                if (!exists) {
                    admins.add(new DomainAdministrator(userId, Instant.now()));
                }
            }
        }).then();
    }
}
