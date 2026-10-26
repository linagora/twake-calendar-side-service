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

import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalendarSharingUpdate;
import com.linagora.calendar.dav.dto.CalendarMirrorSource;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class RegisteredUserDeletionService {
    // Sabre only lists the delegated calendars matching a given invite status: pending and accepted ones are both looked up
    private static final List<Map<String, String>> DELEGATED_CALENDARS_QUERIES = Stream.of("noresponse", "accepted")
        .map(status -> Map.of("sharedDelegationStatus", status))
        .toList();

    private final OpenPaaSUserDAO userDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final CalDavClient calDavClient;

    @Inject
    public RegisteredUserDeletionService(OpenPaaSUserDAO userDAO, OpenPaaSDomainDAO domainDAO, CalDavClient calDavClient) {
        this.userDAO = userDAO;
        this.domainDAO = domainDAO;
        this.calDavClient = calDavClient;
    }

    public Mono<Void> delete(OpenPaaSUser user) {
        return revokeDelegationsGrantedTo(user)
            .then(userDAO.delete(user.username()));
    }

    /**
     * Sabre walks the sharees of a calendar upon each write into it, and fails the write once one of their principals
     * is gone: the user needs to be removed from the sharees of the calendars delegated to them before disappearing.
     * Dropping the mirror held by the user would merely hide it, hence the revocation on the source calendar.
     */
    private Mono<Void> revokeDelegationsGrantedTo(OpenPaaSUser user) {
        CalendarSharingUpdate revocation = CalendarSharingUpdate.revoke(user.username());
        return delegatedCalendars(user)
            .flatMap(sourceCalendar -> revoke(user, sourceCalendar, revocation), DEFAULT_CONCURRENCY)
            .then();
    }

    private Flux<CalendarURL> delegatedCalendars(OpenPaaSUser user) {
        return Flux.fromIterable(DELEGATED_CALENDARS_QUERIES)
            .concatMap(query -> calDavClient.findUserCalendars(user.username(), user.id(), query))
            .flatMapIterable(calendarList -> calendarList.calendars().values())
            .flatMap(calendarMetadata -> Mono.justOrEmpty(CalendarMirrorSource.parse(calendarMetadata).delegatedSource()))
            .distinct();
    }

    private Mono<Void> revoke(OpenPaaSUser user, CalendarURL sourceCalendar, CalendarSharingUpdate revocation) {
        return userDAO.retrieve(sourceCalendar.base())
            .map(owner -> calDavClient.updateCalendarShares(owner.username(), sourceCalendar, revocation))
            .switchIfEmpty(Mono.fromSupplier(() -> revokeOnDomainCalendar(user, sourceCalendar, revocation)))
            .flatMap(Function.identity());
    }

    // Team and resource calendars are owned by no user: they are administrated with the technical token of their domain
    private Mono<Void> revokeOnDomainCalendar(OpenPaaSUser user, CalendarURL sourceCalendar, CalendarSharingUpdate revocation) {
        return Mono.justOrEmpty(user.username().getDomainPart())
            .flatMap(domainDAO::retrieve)
            .flatMap(domain -> calDavClient.updateCalendarShares(domain.id(), sourceCalendar, revocation));
    }
}
