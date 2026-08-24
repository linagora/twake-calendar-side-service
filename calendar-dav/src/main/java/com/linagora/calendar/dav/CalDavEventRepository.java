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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.Strings;
import org.apache.james.core.Username;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Splitter;
import com.linagora.calendar.dav.CalendarEventUpdatePatch.AttendeePartStatusUpdatePatch;
import com.linagora.calendar.dav.dto.CalendarListResponse;
import com.linagora.calendar.dav.dto.CalendarReportJsonResponse;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.eventsearch.EventUid;
import com.linagora.calendar.storage.model.ResourceId;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
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

    /**
     * Updates the participation status of an event stored in a team calendar.
     * <p>
     * Team calendar collections are not writable by the technical token nor by an organizer impersonating
     * the canonical collection directly (missing {DAV:}write-content). A member organizer instead holds a
     * delegated (shadow) calendar in their own home which forwards writes to the source, so the update is
     * applied through that delegated collection.
     */
    public Mono<CalendarReportJsonResponse> updatePartStatForTeamCalendar(OpenPaaSUser organizer,
                                                                         OpenPaaSId teamCalendarId,
                                                                         EventUid eventUid,
                                                                         AttendeePartStatusUpdatePatch patch) {
        return client.findUserCalendarList(organizer)
            .map(CalendarListResponse::calendars)
            .flatMap(calendars -> Mono.justOrEmpty(calendars.entrySet().stream()
                .filter(entry -> isDelegatedForTeamCalendar(entry.getKey(), entry.getValue(), teamCalendarId))
                .map(Map.Entry::getKey)
                .findFirst()))
            .switchIfEmpty(Mono.error(new CalendarEventNotFoundException(organizer.username(), teamCalendarId, eventUid)))
            .flatMap(delegatedCalendar -> {
                URI delegatedEventHref = URI.create(CalendarURL.CALENDAR_URL_PATH_PREFIX + "/"
                    + organizer.id().value() + "/" + delegatedCalendar.calendarId().value() + "/"
                    + eventUid.value() + ".ics");
                return applyModifierToEvent(Mono.just(client.httpClientWithImpersonation(organizer.username())),
                        delegatedEventHref, CalendarEventModifier.of(patch))
                    .then(client.calendarReportByUid(organizer.username(), organizer.id(), eventUid.value()));
            });
    }

    private boolean isDelegatedForTeamCalendar(CalendarURL calendarURL, JsonNode calendarNode, OpenPaaSId teamCalendarId) {
        String id = teamCalendarId.value();
        if (calendarURL.base().value().equals(id) || calendarURL.calendarId().value().equals(id)) {
            return true;
        }
        JsonNode source = calendarNode.path("calendarserver:source");
        if (source.path("id").asText().equals(id) || source.path("calendarHomeId").asText().equals(id)) {
            return true;
        }
        return matchesTeamCalendarHref(source.path("_links").path("self").path("href").asText(null), id)
            || matchesTeamCalendarHref(source.path("href").asText(null), id)
            || matchesTeamCalendarHref(calendarNode.path("calendarserver:delegatedsource").asText(null), id);
    }

    private boolean matchesTeamCalendarHref(String href, String teamCalendarId) {
        if (href == null || href.isBlank()) {
            return false;
        }
        try {
            String path = URI.create(href).getPath();
            if (path.endsWith(".json")) {
                path = path.substring(0, path.length() - ".json".length());
            }
            List<String> parts = Splitter.on('/').omitEmptyStrings().splitToList(path);
            return parts.size() >= 2
                && (parts.get(0).equals(teamCalendarId) || parts.get(1).equals(teamCalendarId));
        } catch (IllegalArgumentException e) {
            return false;
        }
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
            .filter(Predicate.not(this::isEventCancelled))
            .switchIfEmpty(Mono.error(new CalendarEventNotFoundException(calendarEventHref)))
            .map(calendarObject -> calendarObject.withUpdatePatches(modifier))
            .flatMap(updated -> client.updateCalendarEvent(httpClientPublisher, updated))
            .retryWhen(RETRY_UPDATE)
            .onErrorResume(CalendarEventModifier.NoUpdateRequiredException.class, e -> Mono.empty());
    }

    private boolean isEventCancelled(DavCalendarObject calendarObject) {
        return calendarObject.calendarData().getComponents(Component.VEVENT).stream()
            .map(component -> (VEvent) component)
            .findFirst()
            .map(vEvent -> vEvent.getProperty(Property.STATUS)
                .map(Property::getValue)
                .filter(value -> Strings.CI.equals("CANCELLED", value))
                .isPresent())
            .orElse(false);
    }

}
