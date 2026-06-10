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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.linagora.calendar.dav.dto.CalendarListResponse;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;

import it.unimi.dsi.fastutil.Pair;
import reactor.core.publisher.Mono;

public class CalendarSearchSourceResolver {
    private static final String JSON_EXTENSION = ".json";

    private static final Function<JsonNode, Optional<CalendarURL>> SUBSCRIBED_CALENDAR_SOURCE_URL = metadata ->
        Optional.ofNullable(metadata.path("calendarserver:source")
                .path("_links")
                .path("self")
                .path("href").asText(null))
            .filter(StringUtils::isNotBlank)
            .map(URI::create)
            .map(CalendarSearchSourceResolver::toCalendarURL);

    private static final Function<JsonNode, Optional<CalendarURL>> DELEGATED_CALENDAR_SOURCE_URL = metadata ->
        Optional.ofNullable(metadata.path("calendarserver:delegatedsource").asText(null))
            .filter(StringUtils::isNotBlank)
            .map(URI::create)
            .map(CalendarSearchSourceResolver::toCalendarURL);

    private final CalDavClient calDavClient;

    @Inject
    public CalendarSearchSourceResolver(CalDavClient calDavClient) {
        this.calDavClient = calDavClient;
    }

    public Mono<Map<CalendarURL, CalendarURL>> resolve(OpenPaaSUser requester, List<CalendarURL> requestedCalendars) {
        Preconditions.checkNotNull(requester, "requester must not be null");
        Preconditions.checkNotNull(requestedCalendars, "requestedCalendars must not be null");

        if (requestedCalendars.isEmpty()) {
            return Mono.just(Map.of());
        }
        if (isSingleRequesterDefaultCalendar(requester.id(), requestedCalendars)) {
            return Mono.just(Map.of(requestedCalendars.getFirst(), requestedCalendars.getFirst()));
        }
        return calDavClient.findUserCalendarList(requester)
            .map(this::extractCalendarListEntries)
            .map(calendarListEntries -> resolveSearchSourceCalendarURLs(requestedCalendars, calendarListEntries));
    }

    private boolean isSingleRequesterDefaultCalendar(OpenPaaSId requesterId, List<CalendarURL> requestedCalendars) {
        return requestedCalendars.size() == 1
            && requestedCalendars.contains(CalendarURL.from(requesterId));
    }

    private Map<CalendarURL, CalendarURL> resolveSearchSourceCalendarURLs(List<CalendarURL> requestedCalendars,
                                                                          List<CalendarListEntry> calendarListEntries) {

        Map<CalendarURL, CalendarURL> searchSourceByCalendarListURL = calendarListEntries.stream()
            .collect(Collectors.toMap(CalendarListEntry::calendarListURL, CalendarListEntry::searchSourceCalendarURL,
                (first, _) -> first, LinkedHashMap::new));

        Set<CalendarURL> allowedSearchSourceCalendarURLs = Set.copyOf(searchSourceByCalendarListURL.values());

        return requestedCalendars.stream()
            .map(requestedCalendar -> Pair.of(requestedCalendar, searchSourceByCalendarListURL.getOrDefault(requestedCalendar, requestedCalendar)))
            .filter(pair -> allowedSearchSourceCalendarURLs.contains(pair.right()))
            .collect(Collectors.toMap(Pair::left, Pair::right, (firstSearchSource, _) -> firstSearchSource));
    }

    private List<CalendarListEntry> extractCalendarListEntries(CalendarListResponse calendarListResponse) {
        return calendarListResponse.calendars()
            .entrySet()
            .stream()
            .map(calendarListEntry -> new CalendarListEntry(
                calendarListEntry.getKey(),
                SUBSCRIBED_CALENDAR_SOURCE_URL.apply(calendarListEntry.getValue()),
                DELEGATED_CALENDAR_SOURCE_URL.apply(calendarListEntry.getValue())))
            .toList();
    }

    private static CalendarURL toCalendarURL(URI sourceHref) {
        return CalendarURL.parse(Strings.CS.removeEnd(sourceHref.getPath(), JSON_EXTENSION));
    }

    private record CalendarListEntry(CalendarURL calendarListURL,
                                     Optional<CalendarURL> subscribedSourceCalendarURL,
                                     Optional<CalendarURL> delegatedSourceCalendarURL) {
        private CalendarURL searchSourceCalendarURL() {
            return subscribedSourceCalendarURL
                .or(() -> delegatedSourceCalendarURL)
                .orElse(calendarListURL);
        }
    }
}
