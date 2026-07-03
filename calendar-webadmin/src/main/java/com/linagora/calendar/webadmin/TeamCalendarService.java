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

package com.linagora.calendar.webadmin;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;

import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.TeamCalendarInsertRequest;
import com.linagora.calendar.storage.TeamCalendarNotFoundException;
import com.linagora.calendar.storage.TeamCalendarRepository;
import com.linagora.calendar.storage.exception.DomainNotFoundException;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.model.TeamCalendarId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class TeamCalendarService {
    private final OpenPaaSDomainDAO domainDAO;
    private final TeamCalendarRepository teamCalendarRepository;

    @Inject
    public TeamCalendarService(OpenPaaSDomainDAO domainDAO,
                               TeamCalendarRepository teamCalendarRepository) {
        this.domainDAO = domainDAO;
        this.teamCalendarRepository = teamCalendarRepository;
    }

    public Mono<TeamCalendar> create(Domain domainName, String name, String displayName) {
        return resolveDomain(domainName)
            .flatMap(domain -> teamCalendarRepository.create(new TeamCalendarInsertRequest(domain, name, displayName)));
    }

    public Flux<TeamCalendar> list(Domain domainName) {
        return resolveDomain(domainName)
            .flatMapMany(domain -> teamCalendarRepository.listByDomain(domain.id()));
    }

    public Mono<TeamCalendar> retrieve(Domain domainName, TeamCalendarId id) {
        return resolveDomain(domainName)
            .flatMap(domain -> teamCalendarRepository.retrieve(id)
                .filter(teamCalendar -> teamCalendar.domain().id().equals(domain.id()))
                .switchIfEmpty(Mono.error(() -> new TeamCalendarNotFoundException(id))));
    }

    public Mono<TeamCalendar> updateDisplayName(Domain domainName, TeamCalendarId id, String displayName) {
        return retrieve(domainName, id)
            // TODO https://github.com/linagora/twake-calendar-side-service/issues/859
            .then(teamCalendarRepository.updateDisplayName(id, displayName));
    }

    public Mono<Void> delete(Domain domainName, TeamCalendarId id) {
        return resolveDomain(domainName)
            .flatMap(domain -> teamCalendarRepository.retrieve(id)
                .filter(teamCalendar -> teamCalendar.domain().id().equals(domain.id()))
                .flatMap(_ -> teamCalendarRepository.delete(id))
                .then());
    }

    private Mono<OpenPaaSDomain> resolveDomain(Domain domainName) {
        return domainDAO.retrieve(domainName)
            .switchIfEmpty(Mono.error(() -> new DomainNotFoundException(domainName)));
    }
}
