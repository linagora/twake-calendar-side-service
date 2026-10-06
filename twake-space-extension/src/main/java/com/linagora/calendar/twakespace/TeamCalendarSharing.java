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

import java.util.List;
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

    // A team calendar is shared within its domain only, as its webadmin routes require.
    public Mono<Void> share(TeamCalendar teamCalendar, List<SpaceEvent.Member> members) {
        Domain domain = teamCalendar.domain().domain();
        List<SpaceEvent.Member> sharees = members.stream()
            .filter(member -> {
                boolean inDomain = Username.of(member.email()).getDomainPart().equals(Optional.of(domain));
                if (!inDomain) {
                    LOGGER.warn("Not sharing the team calendar of space {} with {}, outside of {}", teamCalendar.name(), member.email(), domain.asString());
                }
                return inDomain;
            })
            .toList();
        if (sharees.isEmpty()) {
            return Mono.empty();
        }
        CalendarSharingUpdate sharingUpdate = CalendarSharingUpdate.grant(sharees.stream()
            .map(member -> CalendarSharingUpdate.AddSharee.of(Username.of(member.email()), davRight(teamCalendar, member)))
            .toArray(CalendarSharingUpdate.AddSharee[]::new));
        return Flux.fromIterable(sharees)
            .concatMap(member -> register(Username.of(member.email())))
            .then(memberService.update(teamCalendar.domainId(), teamCalendar.id(), sharingUpdate));
    }

    public Mono<Void> unshare(TeamCalendar teamCalendar, List<Username> members) {
        return memberService.update(teamCalendar.domainId(), teamCalendar.id(),
            CalendarSharingUpdate.revoke(members.toArray(Username[]::new)));
    }

    public Mono<Void> unshareEveryone(TeamCalendar teamCalendar) {
        return memberService.list(teamCalendar.domainId(), teamCalendar.id())
            .map(TeamCalendarMemberService.TeamCalendarMember::username)
            .collectList()
            .filter(members -> !members.isEmpty())
            .flatMap(members -> unshare(teamCalendar, members));
    }

    // DAV refuses to share with a user the side service does not know.
    private Mono<Void> register(Username username) {
        return userDAO.retrieve(username)
            .switchIfEmpty(Mono.defer(() -> userProvisioner.provisionUser(username)))
            .then();
    }

    // Admins do not get the administration right, so the calendar is never shared outside the space.
    private static DavRight davRight(TeamCalendar teamCalendar, SpaceEvent.Member member) {
        return switch (String.valueOf(member.role())) {
            case "viewer" -> DavRight.READ;
            case "editor", TwakeSpaceProvisioner.ADMIN -> DavRight.READ_WRITE;
            default -> {
                LOGGER.warn("Sharing the team calendar of space {} read only with {}: unknown role {}", teamCalendar.name(), member.email(), member.role());
                yield DavRight.READ;
            }
        };
    }
}
