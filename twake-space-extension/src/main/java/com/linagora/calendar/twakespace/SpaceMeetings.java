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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.amqp.meet.MeetApplicationClient;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavClient.CalendarAccess;
import com.linagora.calendar.dav.dto.CalendarMirrorSource;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.twakespace.model.MeetingRequest;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// The organizer writes the meeting through their own instance of the team calendar, as the calendar frontend does:
// sabre then applies their rights, and publishes the write to the team calendar like any other.
public class SpaceMeetings {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpaceMeetings.class);

    private final OpenPaaSUserDAO userDAO;
    private final CalDavClient calDavClient;
    private final MeetApplicationClient meetClient;
    private final Clock clock;

    @Inject
    public SpaceMeetings(OpenPaaSUserDAO userDAO, CalDavClient calDavClient, MeetApplicationClient meetClient, Clock clock) {
        this.userDAO = userDAO;
        this.calDavClient = calDavClient;
        this.meetClient = meetClient;
        this.clock = clock;
    }

    public Mono<Void> schedule(MeetingRequest request) {
        Username organizer = Username.fromMailAddress(request.organizer());
        CalendarURL teamCalendar = CalendarURL.from(request.teamCalendarId().asOpenPaaSId());
        return userDAO.retrieve(organizer)
            .flatMap(user -> instance(user, teamCalendar))
            .switchIfEmpty(Mono.fromRunnable(() -> LOGGER.warn("Ignoring meeting {} of request {}: team calendar {} is not shared with {}",
                request.uid(), request.id(), teamCalendar.calendarId().value(), organizer.asString())))
            .filterWhen(instance -> calDavClient.calendarReportByUid(organizer, teamCalendar.base(), request.uid())
                .doOnNext(existing -> LOGGER.info("Meeting {} of request {} is already in team calendar {}",
                    request.uid(), request.id(), teamCalendar.calendarId().value()))
                .hasElement()
                .map(exists -> !exists))
            .filterWhen(instance -> calDavClient.resolveCalendarAccess(organizer, instance)
                .map(access -> access == CalendarAccess.WRITABLE)
                .doOnNext(writable -> {
                    if (!writable) {
                        LOGGER.warn("Ignoring meeting {} of request {}: {} may not write to team calendar {}",
                            request.uid(), request.id(), organizer.asString(), teamCalendar.calendarId().value());
                    }
                }))
            .flatMap(instance -> meetClient.fetchToken(request.organizer())
                .flatMap(meetClient::createRoom)
                .map(room -> request.asCalendar(URI.create(room.url().toString()), clock.instant()).toString().getBytes(StandardCharsets.UTF_8))
                .flatMap(calendar -> calDavClient.createCalendarEvent(organizer, instance, request.uid(), calendar))
                .doOnNext(created -> {
                    if (!created) {
                        LOGGER.info("Meeting {} of request {} was written meanwhile: its room stays unused", request.uid(), request.id());
                    }
                }))
            .then();
    }

    private Mono<CalendarURL> instance(OpenPaaSUser user, CalendarURL teamCalendar) {
        return calDavClient.findUserCalendarList(user)
            .flatMapMany(calendars -> Flux.fromIterable(calendars.calendars().entrySet()))
            .filter(calendar -> CalendarMirrorSource.parse(calendar.getValue()).delegatedSource().filter(teamCalendar::equals).isPresent())
            .map(Map.Entry::getKey)
            .next();
    }
}
