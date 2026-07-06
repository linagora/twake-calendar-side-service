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

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Inject;

import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.model.TeamCalendarId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MemoryTeamCalendarRepository implements TeamCalendarRepository {
    private final Map<TeamCalendarId, TeamCalendar> teamCalendars;
    private final Clock clock;

    @Inject
    public MemoryTeamCalendarRepository(Clock clock) {
        this.clock = clock;
        this.teamCalendars = new ConcurrentHashMap<>();
    }

    @Override
    public Mono<TeamCalendar> create(TeamCalendarInsertRequest teamCalendar) {
        return Mono.fromCallable(() -> {
            TeamCalendar created = toTeamCalendar(teamCalendar);
            teamCalendars.put(created.id(), created);
            return created;
        });
    }

    @Override
    public Mono<Void> delete(TeamCalendarId id) {
        return Mono.fromRunnable(() -> teamCalendars.remove(id));
    }

    @Override
    public Mono<TeamCalendar> retrieve(TeamCalendarId id) {
        return Mono.justOrEmpty(teamCalendars.get(id));
    }

    @Override
    public Flux<TeamCalendar> retrieve(OpenPaaSId domainId, String name) {
        return Flux.fromIterable(teamCalendars.values())
            .filter(teamCalendar -> teamCalendar.domain().id().equals(domainId))
            .filter(teamCalendar -> teamCalendar.name().equals(name));
    }

    @Override
    public Mono<Boolean> exists(OpenPaaSId domainId, String name) {
        return retrieve(domainId, name)
            .hasElements();
    }

    @Override
    public Flux<TeamCalendar> listByDomain(OpenPaaSId domainId) {
        return Flux.fromIterable(teamCalendars.values())
            .filter(teamCalendar -> teamCalendar.domain().id().equals(domainId));
    }

    @Override
    public Mono<TeamCalendar> updateDisplayName(TeamCalendarId id, String displayName) {
        return Mono.fromCallable(() -> Optional.ofNullable(teamCalendars.computeIfPresent(id,
                (ignored, existing) -> updateDisplayName(existing, displayName)))
            .orElseThrow(() -> new TeamCalendarNotFoundException(id)));
    }

    private TeamCalendar toTeamCalendar(TeamCalendarInsertRequest request) {
        Instant now = clock.instant();
        return new TeamCalendar(new TeamCalendarId(UUID.randomUUID().toString()), request.domain(),
            request.name(), request.displayName(), now, now);
    }

    private TeamCalendar updateDisplayName(TeamCalendar existing, String displayName) {
        return new TeamCalendar(existing.id(), existing.domain(), existing.name(),
            displayName, existing.creation(), clock.instant());
    }
}
