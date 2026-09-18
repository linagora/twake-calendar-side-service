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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import com.google.common.base.Preconditions;
import com.linagora.calendar.dav.dto.CalendarListResponse;
import com.linagora.calendar.dav.dto.CalendarMirrorSource;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;

import it.unimi.dsi.fastutil.Pair;
import reactor.core.publisher.Mono;

public class CalendarSearchSourceResolver {
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
            .map(CalendarSearchSourceResolver::searchSourceByCalendarListURL)
            .map(searchSources -> resolveSearchSourceCalendarURLs(requestedCalendars, searchSources));
    }

    private boolean isSingleRequesterDefaultCalendar(OpenPaaSId requesterId, List<CalendarURL> requestedCalendars) {
        return requestedCalendars.size() == 1
            && requestedCalendars.contains(CalendarURL.from(requesterId));
    }

    private Map<CalendarURL, CalendarURL> resolveSearchSourceCalendarURLs(List<CalendarURL> requestedCalendars,
                                                                          Map<CalendarURL, CalendarURL> searchSourceByCalendarListURL) {
        Set<CalendarURL> allowedSearchSourceCalendarURLs = Set.copyOf(searchSourceByCalendarListURL.values());

        return requestedCalendars.stream()
            .map(requestedCalendar -> Pair.of(requestedCalendar, searchSourceByCalendarListURL.getOrDefault(requestedCalendar, requestedCalendar)))
            .filter(pair -> allowedSearchSourceCalendarURLs.contains(pair.right()))
            .collect(Collectors.toMap(Pair::left, Pair::right, (firstSearchSource, _) -> firstSearchSource));
    }

    private static Map<CalendarURL, CalendarURL> searchSourceByCalendarListURL(CalendarListResponse calendarListResponse) {
        return calendarListResponse.calendars()
            .entrySet()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                entry -> CalendarMirrorSource.parse(entry.getValue())
                    .sourceCalendarURL()
                    .orElseGet(entry::getKey),
                (first, _) -> first, LinkedHashMap::new));
    }
}
