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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;

import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.TeamCalendarAlreadyExistsException;
import com.linagora.calendar.storage.TeamCalendarRepository;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.model.TeamCalendarId;
import com.linagora.calendar.twakespace.model.DuplicateSpaceTeamCalendarException;
import com.linagora.calendar.twakespace.model.SpaceId;
import com.linagora.calendar.webadmin.TeamCalendarService;

import reactor.core.publisher.Mono;

// The team calendar of a space is the one named after the space id in its domain.
public class SpaceTeamCalendars {
    private static final int OBJECT_ID_BYTES = 12;

    // Derived from the space id, so that concurrent creations of the team calendar of a space collide on its id.
    // 12 bytes, as the MongoDB storage keeps team calendar ids as ObjectIds.
    public static TeamCalendarId teamCalendarId(SpaceId spaceId) {
        byte[] hash = Hashing.sha256().hashString(spaceId.value(), StandardCharsets.UTF_8).asBytes();
        return new TeamCalendarId(BaseEncoding.base16().lowerCase().encode(Arrays.copyOf(hash, OBJECT_ID_BYTES)));
    }

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

    // Team calendar names are not unique: one created by hand with the name of a space would make two.
    public Mono<TeamCalendar> find(Domain domain, SpaceId spaceId) {
        return domainDAO.retrieve(domain)
            .flatMapMany(openPaaSDomain -> teamCalendarRepository.retrieve(openPaaSDomain.id(), spaceId.value()))
            .collectList()
            .flatMap(teamCalendars -> switch (teamCalendars.size()) {
                case 0 -> Mono.empty();
                case 1 -> Mono.just(teamCalendars.getFirst());
                default -> Mono.error(new DuplicateSpaceTeamCalendarException(spaceId, domain, teamCalendars.stream()
                    .map(TeamCalendar::id)
                    .toList()));
            });
    }

    // Without a name yet, the team calendar is created with the space id as display name.
    public Mono<TeamCalendar> findOrCreate(Domain domain, SpaceId spaceId, Optional<String> name) {
        return find(domain, spaceId)
            .switchIfEmpty(Mono.defer(() -> teamCalendarService.create(domain, teamCalendarId(spaceId), spaceId.value(), name.orElse(spaceId.value()))
                .onErrorResume(TeamCalendarAlreadyExistsException.class, alreadyCreated -> find(domain, spaceId)
                    .switchIfEmpty(Mono.error(alreadyCreated)))))
            .flatMap(teamCalendar -> name.filter(newName -> !newName.equals(teamCalendar.displayName()))
                .map(newName -> rename(teamCalendar, newName))
                .orElse(Mono.just(teamCalendar)));
    }

    private Mono<TeamCalendar> rename(TeamCalendar teamCalendar, String name) {
        return teamCalendarService.updateDisplayName(teamCalendar.domain().domain(), teamCalendar.id(), name);
    }
}
