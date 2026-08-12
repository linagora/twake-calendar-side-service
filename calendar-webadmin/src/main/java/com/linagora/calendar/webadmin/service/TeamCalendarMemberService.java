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

package com.linagora.calendar.webadmin.service;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalendarSharingUpdate;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.dav.DavRight;
import com.linagora.calendar.dav.dto.CalendarDetailsResponse.CalendarInvite;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.MailtoUri;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.model.TeamCalendarId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class TeamCalendarMemberService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TeamCalendarMemberService.class);
    private static final Map<String, String> WITH_RIGHTS = Map.of("withRights", "true");

    public enum TeamCalendarRole {
        VIEWER("viewer", DavRight.READ),
        MEMBER("member", DavRight.READ_WRITE),
        MANAGER("manager", DavRight.ADMINISTRATION);

        private final String value;
        private final DavRight davRight;

        TeamCalendarRole(String value, DavRight davRight) {
            this.value = value;
            this.davRight = davRight;
        }

        public String value() {
            return value;
        }

        public String davRight() {
            return davRight.value();
        }

        public static TeamCalendarRole fromDavAccess(int davAccess) {
            return DavRight.fromAccess(davAccess)
                .flatMap(TeamCalendarRole::fromDavRight)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported DAV delegation access: " + davAccess));
        }

        private static Optional<TeamCalendarRole> fromDavRight(DavRight davRight) {
            return Stream.of(values())
                .filter(role -> role.davRight == davRight)
                .findFirst();
        }
    }

    public record TeamCalendarMember(Username username, TeamCalendarRole role) {
    }

    private final CalDavClient calDavClient;

    @Inject
    public TeamCalendarMemberService(CalDavClient calDavClient) {
        this.calDavClient = calDavClient;
    }

    public Flux<TeamCalendarMember> list(OpenPaaSId domainId, TeamCalendarId teamCalendarId) {
        CalendarURL calendarURL = toCalendarURL(teamCalendarId);
        return calDavClient.fetchCalendarDetails(domainId, calendarURL, WITH_RIGHTS)
            .doOnError(DavClientException.class,
                error -> LOGGER.warn("Failed to fetch team calendar '{}' details from DAV", calendarURL.asUri(), error))
            .flatMapMany(response -> Flux.fromIterable(response.invites()))
            .filter(invite -> MailtoUri.hasMailtoPrefix(invite.href()))
            .filter(invite -> invite.access().isPresent())
            .map(TeamCalendarMemberService::toTeamCalendarMember);
    }

    public Mono<Void> update(OpenPaaSId domainId, TeamCalendarId teamCalendarId, CalendarSharingUpdate sharingUpdate) {
        CalendarURL calendarURL = toCalendarURL(teamCalendarId);
        return calDavClient.updateCalendarShares(domainId, calendarURL, sharingUpdate)
            .doOnError(DavClientException.class,
                error -> LOGGER.warn("Failed to update team calendar '{}' shares in DAV", calendarURL.asUri(), error));
    }

    private static CalendarURL toCalendarURL(TeamCalendarId teamCalendarId) {
        return CalendarURL.from(teamCalendarId.asOpenPaaSId());
    }

    private static TeamCalendarMember toTeamCalendarMember(CalendarInvite invite) {
        return new TeamCalendarMember(Username.of(MailtoUri.stripMailtoPrefix(invite.href())),
            TeamCalendarRole.fromDavAccess(invite.access().get()));
    }
}
