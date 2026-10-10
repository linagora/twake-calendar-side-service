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

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.twakespace.model.ActivityEvent;
import com.linagora.calendar.twakespace.model.SpaceEvent;
import com.linagora.calendar.twakespace.model.SpaceEventType;
import com.linagora.calendar.twakespace.model.TwakeSpace;
import com.linagora.calendar.twakespace.model.UnprocessableSpaceEventException;
import com.linagora.calendar.twakespace.storage.TwakeSpaceRepository;

import reactor.core.publisher.Mono;

// Events of a space may be handled in any order, concurrently. Each one is merged into the stored space,
// then the team calendar is brought in line with the stored space rather than with the event.
public class TwakeSpaceProvisioner {
    private static final Logger LOGGER = LoggerFactory.getLogger(TwakeSpaceProvisioner.class);
    static final String ADMIN = "admin";
    private static final int MAX_APPLY_ATTEMPTS = 10;

    private final TwakeSpaceRepository spaceRepository;
    private final SpaceTeamCalendars teamCalendars;
    private final TeamCalendarSharing sharing;
    private final Clock clock;

    @Inject
    public TwakeSpaceProvisioner(TwakeSpaceRepository spaceRepository, SpaceTeamCalendars teamCalendars, TeamCalendarSharing sharing,
                                 Clock clock) {
        this.spaceRepository = spaceRepository;
        this.teamCalendars = teamCalendars;
        this.sharing = sharing;
        this.clock = clock;
    }

    // A redelivered created publishes the provisioned event again, as its first publication may have failed.
    public Mono<ActivityEvent> handle(SpaceEventType type, SpaceEvent event) {
        return Mono.fromCallable(() -> change(type, event))
            .flatMap(spaceRepository::merge)
            .flatMap(space -> apply(space, MAX_APPLY_ATTEMPTS))
            .filter(_ -> type == SpaceEventType.CREATED)
            .map(teamCalendar -> ActivityEvent.provisioned(event.organizationId(), event.id(), teamCalendar.id(), clock.instant()));
    }

    // Another event of the space may have been merged while this one was applied, and its own apply may have
    // finished first: applying again until the space stays the same makes the last write follow the stored space.
    private Mono<TeamCalendar> apply(TwakeSpace space, int attempts) {
        if (attempts == 0) {
            return Mono.error(new IllegalStateException("Space " + space.id() + " kept changing while its team calendar was updated"));
        }
        return applyOnce(space)
            .flatMap(teamCalendar -> spaceRepository.retrieve(space.id())
                .flatMap(latest -> latest.equals(space) ? Mono.just(teamCalendar) : apply(latest, attempts - 1)));
    }

    // A deleted space keeps its team calendar, shared with no one, and does not get one if it had none.
    private Mono<TeamCalendar> applyOnce(TwakeSpace space) {
        if (space.domain().isEmpty()) {
            LOGGER.info("Space {} has no known domain yet: its team calendar waits for an event naming one", space.id());
            return Mono.empty();
        }
        Domain domain = space.domain().get();
        Mono<TeamCalendar> teamCalendar = space.isDeleted()
            ? teamCalendars.find(domain, space.id())
            : teamCalendars.findOrCreate(domain, space.id(), space.name().map(TwakeSpace.Name::value));
        return teamCalendar.flatMap(calendar -> sharing.apply(calendar, space).thenReturn(calendar));
    }

    private static TwakeSpace change(SpaceEventType type, SpaceEvent event) {
        TwakeSpace space = TwakeSpace.of(event.id(), Optional.ofNullable(event.organizationId()), domain(event));
        return switch (type) {
            case CREATED -> {
                if (event.organizationId() == null) {
                    throw new UnprocessableSpaceEventException("Space " + event.id() + " has no organization");
                }
                if (event.name() == null) {
                    throw new UnprocessableSpaceEventException("Space " + event.id() + " has no name");
                }
                yield space.withName(new TwakeSpace.Name(event.name(), event.timestamp()))
                    .withMembers(memberships(event, member -> TwakeSpace.Membership.role(String.valueOf(member.role()), event.timestamp())));
            }
            case UPDATED -> Optional.ofNullable(event.name())
                .map(name -> space.withName(new TwakeSpace.Name(name, event.timestamp())))
                .orElse(space);
            case MEMBER_ADDED, MEMBER_ROLE_CHANGED ->
                space.withMembers(memberships(event, member -> TwakeSpace.Membership.role(String.valueOf(member.role()), event.timestamp())));
            case MEMBER_REMOVED -> space.withMembers(memberships(event, _ -> TwakeSpace.Membership.removed(event.timestamp())));
            case DELETED -> space.withDeletion(event.timestamp());
        };
    }

    private static Map<Username, TwakeSpace.Membership> memberships(SpaceEvent event, Function<SpaceEvent.Member, TwakeSpace.Membership> membership) {
        return event.members().stream()
            .collect(Collectors.toMap(member -> Username.of(member.email()), membership, (first, second) -> second));
    }

    // organizationDomain is absent when the organization has none: the domain of an admin stands for it.
    private static Optional<Domain> domain(SpaceEvent event) {
        if (event.organizationDomain() != null) {
            return Optional.of(Domain.of(event.organizationDomain()));
        }
        return event.members().stream()
            .filter(member -> ADMIN.equals(member.role()))
            .findFirst()
            .flatMap(admin -> Username.of(admin.email()).getDomainPart());
    }
}
