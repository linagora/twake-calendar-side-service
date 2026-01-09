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

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.core.Username;

import com.linagora.calendar.dav.CalendarEventUpdatePatch.AttendeePartStatusUpdatePatch;
import com.linagora.calendar.dav.dto.CalendarReportJsonResponse;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.eventsearch.EventUid;
import com.linagora.calendar.storage.model.ResourceId;

import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

public class CalDavEventRepository {

    private static final int MAX_CALENDAR_OBJECT_UPDATE_RETRIES = 5;

    private static final Duration CALENDAR_OBJECT_UPDATE_RETRY_BACKOFF = Optional.ofNullable(System.getProperty("MIN_CALENDAR_OBJECT_UPDATE_RETRY_BACKOFF_IN_MILLS"))
        .map(Long::parseLong)
        .map(Duration::ofMillis)
        .orElse(Duration.ofMillis(100));

    private static final Retry RETRY_NOT_FOUND =
        Retry.backoff(1, Duration.ofSeconds(1))
            .filter(CalendarEventNotFoundException.class::isInstance)
            .onRetryExhaustedThrow((retrySpec, retrySignal) -> retrySignal.failure());

    private static final Retry RETRY_UPDATE =
        Retry.backoff(MAX_CALENDAR_OBJECT_UPDATE_RETRIES, CALENDAR_OBJECT_UPDATE_RETRY_BACKOFF)
            .filter(CalDavClient.RetriableDavClientException.class::isInstance)
            .onRetryExhaustedThrow((spec, signal) ->
                new DavClientException("Max retries exceeded for calendar update", signal.failure()));


    private final CalDavClient client;

    @Singleton
    @Inject
    public CalDavEventRepository(CalDavClient client) {
        this.client = client;
    }

    public Mono<Void> updateEvent(Username username, OpenPaaSId calendarId, EventUid eventUid, CalendarEventModifier modifier) {
        return applyModifierByEventUid(username, calendarId, eventUid, modifier);
    }

    public Mono<CalendarReportJsonResponse> updatePartStat(Username username, OpenPaaSId calendarId, EventUid eventUid, PartStat partStat) {
        AttendeePartStatusUpdatePatch attendeePartStatusUpdatePatch = new AttendeePartStatusUpdatePatch(username, partStat);
        return updatePartStat(username, calendarId, eventUid, attendeePartStatusUpdatePatch);
    }

    public Mono<CalendarReportJsonResponse> updatePartStat(Username username, OpenPaaSId calendarId, EventUid eventUid, AttendeePartStatusUpdatePatch patch) {
        CalendarEventModifier modifier = CalendarEventModifier.of(patch);
        return applyModifierByEventUid(username, calendarId, eventUid, modifier)
            .then(client.calendarReportByUid(username, calendarId, eventUid.value()));
    }

    public Mono<Void> updatePartStat(OpenPaaSDomain openPaaSDomain, ResourceId resourceId, String eventPathId, PartStat partStat) {
        URI calendarEventHref = URI.create("/calendars/")
            .resolve(resourceId.value() + "/")
            .resolve(resourceId.value() + "/")
            .resolve(eventPathId + ".ics");
        Username resourceUsername = Username.fromLocalPartWithDomain(resourceId.value(), openPaaSDomain.domain());
        AttendeePartStatusUpdatePatch attendeePartStatusUpdatePatch = new AttendeePartStatusUpdatePatch(resourceUsername, partStat);
        return applyModifierToEvent(client.httpClientWithTechnicalToken(openPaaSDomain.id()),
            calendarEventHref, CalendarEventModifier.of(attendeePartStatusUpdatePatch));
    }

    private Mono<Void> applyModifierByEventUid(Username username, OpenPaaSId calendarId, EventUid eventUid, CalendarEventModifier modifier) {
        return client.calendarReportByUid(username, calendarId, eventUid.value())
            .map(CalendarReportJsonResponse::calendarHref)
            .switchIfEmpty(Mono.error(new CalendarEventNotFoundException(username, calendarId, eventUid)))
            .flatMap(href -> applyModifierToEvent(Mono.just(client.httpClientWithImpersonation(username)), href, modifier));
    }

    private Mono<Void> applyModifierToEvent(Mono<HttpClient> httpClientPublisher,
                                            URI calendarEventHref,
                                            CalendarEventModifier modifier) {
        return client.fetchCalendarEvent(httpClientPublisher, calendarEventHref)
            .switchIfEmpty(Mono.error(new CalendarEventNotFoundException(calendarEventHref)))
            .retryWhen(RETRY_NOT_FOUND)
            .map(calendarObject -> calendarObject.withUpdatePatches(modifier))
            .flatMap(updated -> client.updateCalendarEvent(httpClientPublisher, updated))
            .retryWhen(RETRY_UPDATE)
            .onErrorResume(CalendarEventModifier.NoUpdateRequiredException.class, e -> Mono.empty());
    }

}
