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

package com.linagora.calendar.twakespace;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;

import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.TeamCalendarRepository;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.webadmin.TeamCalendarService;

import reactor.core.publisher.Mono;

// The team calendar of a space is the one named after the space id in its domain.
public class SpaceTeamCalendars {
    private final OpenPaaSDomainDAO domainDAO;
    private final TeamCalendarRepository teamCalendarRepository;
    private final TeamCalendarService teamCalendarService;

    @Inject
    public SpaceTeamCalendars(OpenPaaSDomainDAO domainDAO, TeamCalendarRepository teamCalendarRepository,
                              TeamCalendarService teamCalendarService) {
        this.domainDAO = domainDAO;
        this.teamCalendarRepository = teamCalendarRepository;
        this.teamCalendarService = teamCalendarService;
    }

    public Mono<TeamCalendar> find(Domain domain, SpaceId spaceId) {
        return domainDAO.retrieve(domain)
            .flatMapMany(openPaaSDomain -> teamCalendarRepository.retrieve(openPaaSDomain.id(), spaceId.value()))
            .next();
    }

    // A created that failed halfway may have left its team calendar behind: complete it rather than create a second one.
    public Mono<TeamCalendar> findOrCreate(Domain domain, SpaceId spaceId, String name) {
        return find(domain, spaceId)
            .flatMap(existing -> rename(existing, name))
            .switchIfEmpty(Mono.defer(() -> teamCalendarService.create(domain, spaceId.value(), name)));
    }

    public Mono<TeamCalendar> rename(TeamCalendar teamCalendar, String name) {
        return teamCalendarService.updateDisplayName(teamCalendar.domain().domain(), teamCalendar.id(), name);
    }
}
