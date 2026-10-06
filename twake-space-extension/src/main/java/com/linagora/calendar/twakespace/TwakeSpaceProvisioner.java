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
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.storage.model.TeamCalendar;

import reactor.core.publisher.Mono;

public class TwakeSpaceProvisioner {
    private static final Logger LOGGER = LoggerFactory.getLogger(TwakeSpaceProvisioner.class);
    static final String CREATED = "twake.space.created";
    static final String UPDATED = "twake.space.updated";
    static final String DELETED = "twake.space.deleted";
    static final String MEMBER_ADDED = "twake.space.member.added";
    static final String MEMBER_ROLE_CHANGED = "twake.space.member.role.changed";
    static final String MEMBER_REMOVED = "twake.space.member.removed";
    static final String ADMIN = "admin";

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

    // Emits the activity event to publish once the space event is applied.
    public Mono<ActivityEvent> handle(String routingKey, SpaceEvent event) {
        return switch (routingKey) {
            case CREATED -> created(event);
            case UPDATED -> Mono.justOrEmpty(event.name())
                .flatMap(name -> liveTeamCalendar(routingKey, event.id())
                    .flatMap(teamCalendar -> teamCalendars.rename(teamCalendar, name)))
                .then(Mono.empty());
            case MEMBER_ADDED, MEMBER_ROLE_CHANGED -> liveTeamCalendar(routingKey, event.id())
                .flatMap(teamCalendar -> sharing.share(teamCalendar, event.members()))
                .then(Mono.empty());
            case MEMBER_REMOVED -> liveTeamCalendar(routingKey, event.id())
                .flatMap(teamCalendar -> sharing.unshare(teamCalendar, event.members().stream()
                    .map(member -> Username.of(member.email()))
                    .toList()))
                .then(Mono.empty());
            case DELETED -> provisionedSpace(event.id())
                .flatMap(this::teamCalendar)
                .flatMap(teamCalendar -> spaceRepository.markDeleted(event.id())
                    .then(sharing.unshareEveryone(teamCalendar)))
                .then(Mono.empty());
            default -> Mono.fromRunnable(() -> LOGGER.warn("Ignoring {} for space {}: not a space event the extension handles", routingKey, event.id()));
        };
    }

    // The space is recorded last: a recorded space was fully provisioned, so a replayed created cannot undo later events.
    // A redelivered created publishes the provisioned event again, as its first publication may have failed.
    private Mono<ActivityEvent> created(SpaceEvent event) {
        if (event.organizationId() == null) {
            return Mono.error(new UnprocessableSpaceEventException("Space " + event.id() + " has no organization"));
        }
        return spaceRepository.retrieve(event.id())
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(provisioned -> provisioned
                .map(space -> alreadyCreated(space, event.organizationId()))
                .orElseGet(() -> create(event)));
    }

    private Mono<ActivityEvent> alreadyCreated(TwakeSpaceRepository.TwakeSpace space, String organization) {
        if (space.deletion().isPresent()) {
            LOGGER.info("Ignoring {} for space {}: the space is deleted", CREATED, space.id());
            return Mono.empty();
        }
        return teamCalendar(space)
            .map(teamCalendar -> provisioned(organization, space.id(), teamCalendar));
    }

    private Mono<ActivityEvent> create(SpaceEvent event) {
        if (event.name() == null) {
            return Mono.error(new UnprocessableSpaceEventException("Space " + event.id() + " has no name"));
        }
        Domain domain = domain(event);
        return teamCalendars.findOrCreate(domain, event.id(), event.name())
            .flatMap(teamCalendar -> sharing.share(teamCalendar, event.members())
                .then(spaceRepository.save(event.id(), event.organizationId(), domain))
                .thenReturn(provisioned(event.organizationId(), event.id(), teamCalendar)));
    }

    private ActivityEvent provisioned(String organization, String spaceId, TeamCalendar teamCalendar) {
        return ActivityEvent.provisioned(organization, spaceId, teamCalendar.id(), clock.instant());
    }

    // A deleted space stays deleted: a late event must not share its calendar again.
    private Mono<TeamCalendar> liveTeamCalendar(String routingKey, String spaceId) {
        return provisionedSpace(spaceId)
            .filter(space -> {
                if (space.deletion().isPresent()) {
                    LOGGER.info("Ignoring {} for space {}: the space is deleted", routingKey, spaceId);
                }
                return space.deletion().isEmpty();
            })
            .flatMap(this::teamCalendar);
    }

    // Dead lettered rather than dropped, so that the spaces missing their team calendar show.
    private Mono<TwakeSpaceRepository.TwakeSpace> provisionedSpace(String spaceId) {
        return spaceRepository.retrieve(spaceId)
            .switchIfEmpty(Mono.error(() -> noTeamCalendar(spaceId)));
    }

    private Mono<TeamCalendar> teamCalendar(TwakeSpaceRepository.TwakeSpace space) {
        return teamCalendars.find(space.domain(), space.id())
            .switchIfEmpty(Mono.error(() -> noTeamCalendar(space.id())));
    }

    private static UnprocessableSpaceEventException noTeamCalendar(String spaceId) {
        return new UnprocessableSpaceEventException("Space " + spaceId + " has no team calendar");
    }

    // organizationDomain is absent when the organization has none: the domain of the first admin stands for it.
    private static Domain domain(SpaceEvent event) {
        if (event.organizationDomain() != null) {
            return Domain.of(event.organizationDomain());
        }
        return event.members().stream()
            .filter(member -> ADMIN.equals(member.role()))
            .findFirst()
            .flatMap(admin -> Username.of(admin.email()).getDomainPart())
            .orElseThrow(() -> new UnprocessableSpaceEventException("Space " + event.id() + " has no admin"));
    }
}
