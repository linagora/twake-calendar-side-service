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

package com.linagora.calendar.dav;

import static com.linagora.calendar.dav.CalDavClient.DEFAULT_FIND_USER_CALENDARS_PARAMS;
import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.util.Map;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.user.api.DeleteUserDataTaskStep;

import com.linagora.calendar.dav.CalDavClient.PublicRight;
import com.linagora.calendar.dav.CalendarSharingUpdate.RemoveSharee;
import com.linagora.calendar.dav.dto.CalendarDetailsResponse.CalendarInvite;
import com.linagora.calendar.dav.dto.CalendarMirrorSource;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.MailtoUri;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class DavCalendarDeletionTaskStep implements DeleteUserDataTaskStep {
    private static final Map<String, String> WITH_RIGHTS = Map.of("withRights", "true");

    private final CalDavClient calDavClient;
    private final OpenPaaSUserDAO openPaaSUserDAO;

    @Inject
    public DavCalendarDeletionTaskStep(CalDavClient calDavClient, OpenPaaSUserDAO openPaaSUserDAO) {
        this.calDavClient = calDavClient;
        this.openPaaSUserDAO = openPaaSUserDAO;
    }

    @Override
    public StepName name() {
        return new StepName("DavCalendarDeletionTaskStep");
    }

    @Override
    public int priority() {
        return 1;
    }

    @Override
    public Mono<Void> deleteUserData(Username username) {
        return openPaaSUserDAO.retrieve(username)
            .flatMapMany(openPaaSUser -> calDavClient.findUserCalendars(username, openPaaSUser.id(), DEFAULT_FIND_USER_CALENDARS_PARAMS)
                .flatMapIterable(calendarList -> calendarList.calendars().entrySet())
                .flatMap(calendar -> deleteCalendar(username, calendar.getKey(), CalendarMirrorSource.parse(calendar.getValue())),
                    DEFAULT_CONCURRENCY))
            .then();
    }

    private Mono<Void> deleteCalendar(Username username, CalendarURL calendarURL, CalendarMirrorSource mirrorSource) {
        if (mirrorSource.isMirror()) {
            // Dropping the mirror unsubscribes the deleted user from, or hands back the delegation of, a calendar owned by somebody else.
            return calDavClient.deleteCalendar(username, calendarURL);
        }
        return revokeSharings(username, calendarURL)
            .then(calDavClient.updateCalendarAcl(username, calendarURL, PublicRight.HIDE_ALL_EVENT))
            .then(deleteOwnedCalendar(username, calendarURL));
    }

    /**
     * Sharees keep a mirror of the calendar in their own calendar home, which outlives the deletion of the
     * underlying data: revoking their rights beforehand is what actually tears those mirrors down.
     */
    private Mono<Void> revokeSharings(Username username, CalendarURL calendarURL) {
        return calDavClient.fetchCalendarDetails(username, calendarURL, WITH_RIGHTS)
            .map(calendarDetails -> calendarDetails.invites().stream()
                .filter(DavCalendarDeletionTaskStep::isSharee)
                .map(invite -> RemoveSharee.of(Username.of(MailtoUri.stripMailtoPrefix(invite.href()))))
                .toList())
            .filter(sharees -> !sharees.isEmpty())
            .flatMap(sharees -> calDavClient.updateCalendarShares(username, calendarURL,
                CalendarSharingUpdate.builder().revokeAll(sharees).build()));
    }

    private static boolean isSharee(CalendarInvite invite) {
        return MailtoUri.hasMailtoPrefix(invite.href())
            && invite.access().flatMap(DavRight::fromAccess).isPresent();
    }

    private Mono<Void> deleteOwnedCalendar(Username username, CalendarURL calendarURL) {
        boolean isPrimary = calendarURL.base().equals(calendarURL.calendarId());
        if (isPrimary) {
            return deletePrimaryCalendarEvents(username, calendarURL).then();
        }
        return calDavClient.deleteCalendar(username, calendarURL);
    }

    private Flux<Void> deletePrimaryCalendarEvents(Username username, CalendarURL calendarURL) {
        return calDavClient.findUserCalendarEventIds(username, calendarURL)
            .flatMap(eventId -> calDavClient.deleteCalendarEvent(username, calendarURL, eventId),
                DEFAULT_CONCURRENCY);
    }
}
