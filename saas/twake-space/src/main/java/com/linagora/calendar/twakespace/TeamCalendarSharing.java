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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.CalendarSharingUpdate;
import com.linagora.calendar.dav.DavRight;
import com.linagora.calendar.saas.SaaSUserProvisioner;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.twakespace.model.TwakeSpace;
import com.linagora.calendar.webadmin.service.TeamCalendarMemberService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class TeamCalendarSharing {
    private static final Logger LOGGER = LoggerFactory.getLogger(TeamCalendarSharing.class);

    private final TeamCalendarMemberService memberService;
    private final OpenPaaSUserDAO userDAO;
    private final SaaSUserProvisioner userProvisioner;

    @Inject
    public TeamCalendarSharing(TeamCalendarMemberService memberService, OpenPaaSUserDAO userDAO, SaaSUserProvisioner userProvisioner) {
        this.memberService = memberService;
        this.userDAO = userDAO;
        this.userProvisioner = userProvisioner;
    }

    // Changes only the shares that differ from the space, so that applying a space twice writes nothing.
    // A deleted space is shared with no one. Otherwise only the members the space knows are touched.
    public Mono<Void> apply(TeamCalendar teamCalendar, TwakeSpace space) {
        Map<Username, DavRight> wanted = space.isDeleted() ? Map.of() : sharees(teamCalendar, space.members());
        return memberService.list(teamCalendar.domainId(), teamCalendar.id())
            .collectMap(TeamCalendarMemberService.TeamCalendarMember::username, member -> member.role().davRight())
            .flatMap(current -> {
                List<Username> grants = wanted.keySet().stream()
                    .filter(username -> !wanted.get(username).value().equals(current.get(username)))
                    .toList();
                List<Username> revokes = current.keySet().stream()
                    .filter(username -> space.isDeleted() || Optional.ofNullable(space.members().get(username))
                        .map(membership -> membership.role().isEmpty())
                        .orElse(false))
                    .toList();
                if (grants.isEmpty() && revokes.isEmpty()) {
                    return Mono.empty();
                }
                CalendarSharingUpdate.Builder update = CalendarSharingUpdate.builder();
                grants.forEach(username -> update.grant(username, wanted.get(username)));
                revokes.forEach(update::revoke);
                return Flux.fromIterable(grants)
                    .concatMap(this::register)
                    .then(memberService.update(teamCalendar.domainId(), teamCalendar.id(), update.build()));
            });
    }

    // A team calendar is shared within its domain only, as its webadmin routes require.
    private static Map<Username, DavRight> sharees(TeamCalendar teamCalendar, Map<Username, TwakeSpace.Membership> members) {
        Domain domain = teamCalendar.domain().domain();
        Map<Username, DavRight> sharees = new HashMap<>();
        members.forEach((username, membership) -> membership.role().ifPresent(role -> {
            if (username.getDomainPart().equals(Optional.of(domain))) {
                sharees.put(username, davRight(teamCalendar, username, role));
            } else {
                LOGGER.warn("Not sharing the team calendar of space {} with {}, outside of {}", teamCalendar.name(), username.asString(), domain.asString());
            }
        }));
        return sharees;
    }

    // DAV refuses to share with a user the side service does not know.
    private Mono<Void> register(Username username) {
        return userDAO.retrieve(username)
            .switchIfEmpty(Mono.defer(() -> userProvisioner.provisionUser(username)))
            .then();
    }

    // Admins do not get the administration right, so the calendar is never shared outside the space.
    private static DavRight davRight(TeamCalendar teamCalendar, Username username, String role) {
        return switch (role) {
            case "viewer" -> DavRight.READ;
            case "editor", TwakeSpaceProvisioner.ADMIN -> DavRight.READ_WRITE;
            default -> {
                LOGGER.warn("Sharing the team calendar of space {} read only with {}: unknown role {}", teamCalendar.name(), username.asString(), role);
                yield DavRight.READ;
            }
        };
    }
}
