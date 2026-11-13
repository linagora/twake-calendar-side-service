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
import com.linagora.calendar.storage.TeamCalendarRepository;
import com.linagora.calendar.storage.model.TeamCalendarId;

import it.unimi.dsi.fastutil.Pair;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class CalendarSearchSourceResolver {
    /**
     * A source calendar to search, and whether its PRIVATE / CONFIDENTIAL events must stay hidden from the
     * requester, as esn-sabre reduces them to busy blocks for anyone but their owner.
     */
    public record SearchSource(CalendarURL calendar, boolean hidesPrivateEvents) {
        public SearchSource {
            Preconditions.checkNotNull(calendar, "calendar must not be null");
        }
    }

    private record CalendarList(Map<CalendarURL, CalendarURL> searchSourceByCalendarListURL,
                                Set<CalendarURL> delegatedSources) {
    }

    private final CalDavClient calDavClient;
    private final TeamCalendarRepository teamCalendarRepository;

    @Inject
    public CalendarSearchSourceResolver(CalDavClient calDavClient, TeamCalendarRepository teamCalendarRepository) {
        this.calDavClient = calDavClient;
        this.teamCalendarRepository = teamCalendarRepository;
    }

    /**
     * Resolves the requested calendars like {@link #resolve(OpenPaaSUser, List)}, and tells for each source
     * whether the requester may read its PRIVATE / CONFIDENTIAL events. As in esn-sabre
     * ({@code Utils::hidesPrivateEventsFrom}) only the owner of the source calendar may, and the members of a
     * team calendar, who read it through the instance it is shared to them with. Delegates, subscribers and
     * resource administrators may not.
     */
    public Mono<List<SearchSource>> resolveSearchSources(OpenPaaSUser requester, List<CalendarURL> requestedCalendars) {
        Preconditions.checkNotNull(requester, "requester must not be null");
        Preconditions.checkNotNull(requestedCalendars, "requestedCalendars must not be null");

        if (requestedCalendars.isEmpty()) {
            return Mono.just(List.of());
        }
        if (isSingleRequesterDefaultCalendar(requester.id(), requestedCalendars)) {
            return Mono.just(List.of(new SearchSource(requestedCalendars.getFirst(), false)));
        }
        return calDavClient.findUserCalendarList(requester)
            .map(CalendarSearchSourceResolver::calendarList)
            .flatMap(calendarList -> Flux.fromIterable(Set.copyOf(resolveSearchSourceCalendarURLs(requestedCalendars,
                    calendarList.searchSourceByCalendarListURL()).values()))
                .concatMap(source -> hidesPrivateEvents(requester, source, calendarList)
                    .map(hidesPrivateEvents -> new SearchSource(source, hidesPrivateEvents)))
                .collectList());
    }

    private Mono<Boolean> hidesPrivateEvents(OpenPaaSUser requester, CalendarURL source, CalendarList calendarList) {
        if (source.base().equals(requester.id())) {
            return Mono.just(false);
        }
        if (!calendarList.delegatedSources().contains(source)) {
            return Mono.just(true);
        }
        return teamCalendarRepository.retrieve(TeamCalendarId.from(source.base()))
            .hasElement()
            .map(isTeamCalendar -> !isTeamCalendar);
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
            .map(calendarListResponse -> calendarList(calendarListResponse).searchSourceByCalendarListURL())
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

    private static CalendarList calendarList(CalendarListResponse calendarListResponse) {
        Map<CalendarURL, CalendarMirrorSource> mirrorSourceByCalendarListURL = calendarListResponse.calendars()
            .entrySet()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                entry -> CalendarMirrorSource.parse(entry.getValue()),
                (first, _) -> first, LinkedHashMap::new));

        // A subscription whose source esn-sabre lists for free/busy only grants no read access to its events.
        Map<CalendarURL, CalendarURL> searchSourceByCalendarListURL = mirrorSourceByCalendarListURL.entrySet()
            .stream()
            .filter(entry -> entry.getValue().subscribedSource().isEmpty() || entry.getValue().subscribedSourceReadable())
            .collect(Collectors.toMap(Map.Entry::getKey,
                entry -> entry.getValue().readableSourceCalendarURL().orElseGet(entry::getKey),
                (first, _) -> first, LinkedHashMap::new));

        // A calendar shared to the requester, as opposed to subscribed to: how team calendar members read it.
        Set<CalendarURL> delegatedSources = mirrorSourceByCalendarListURL.values()
            .stream()
            .filter(mirrorSource -> mirrorSource.subscribedSource().isEmpty())
            .flatMap(mirrorSource -> mirrorSource.delegatedSource().stream())
            .collect(Collectors.toUnmodifiableSet());

        return new CalendarList(searchSourceByCalendarListURL, delegatedSources);
    }
}
