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

import java.time.Duration;
import java.util.Optional;
import java.util.function.UnaryOperator;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.core.Username;

import com.linagora.calendar.dav.CalendarEventUpdatePatch.AttendeePartStatusUpdatePatch;
import com.linagora.calendar.dav.dto.CalendarEventReportResponse;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.eventsearch.EventUid;

import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public class CalDavEventRepository {

    private static final int MAX_CALENDAR_OBJECT_UPDATE_RETRIES = 5;

    private static final Duration CALENDAR_OBJECT_UPDATE_RETRY_BACKOFF = Optional.ofNullable(System.getProperty("MIN_CALENDAR_OBJECT_UPDATE_RETRY_BACKOFF_IN_MILLS"))
        .map(Long::parseLong)
        .map(Duration::ofMillis)
        .orElse(Duration.ofMillis(100));

    private final CalDavClient client;

    @Singleton
    @Inject
    public CalDavEventRepository(CalDavClient client) {
        this.client = client;
    }

    public Mono<Void> updateEvent(Username username, OpenPaaSId calendarId, EventUid eventUid, CalendarEventModifier eventModifier) {
        UnaryOperator<DavCalendarObject> updateEventOperator = calendarObject -> calendarObject.withUpdatePatches(eventModifier);

        return client.calendarReportByUid(username, calendarId, eventUid.value())
            .map(CalendarEventReportResponse::calendarHref)
            .flatMap(calendarEventHref -> client.fetchCalendarEvent(username, calendarEventHref))
            .switchIfEmpty(Mono.defer(() -> Mono.error(new CalendarEventNotFoundException(username, calendarId, eventUid))))
            .map(updateEventOperator)
            .flatMap(updatedCalendarObject -> client.updateCalendarEvent(username, updatedCalendarObject))
            .retryWhen(Retry.backoff(MAX_CALENDAR_OBJECT_UPDATE_RETRIES, CALENDAR_OBJECT_UPDATE_RETRY_BACKOFF)
                .filter(CalDavClient.RetriableDavClientException.class::isInstance)
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                    new DavClientException("Max retries exceeded for calendar update", retrySignal.failure())))
            .onErrorResume(error -> {
                if (error instanceof CalendarEventModifier.NoUpdateRequiredException) {
                    return Mono.empty();
                }
                return Mono.error(error);
            });
    }

    public Mono<CalendarEventReportResponse> updatePartStat(Username username, OpenPaaSId calendarId, EventUid eventUid, PartStat partStat) {
        CalendarEventModifier eventModifier = CalendarEventModifier.of(new AttendeePartStatusUpdatePatch(username, partStat));
        return updateEvent(username, calendarId, eventUid, eventModifier)
            .then(client.calendarReportByUid(username, calendarId, eventUid.value()));
    }

}
