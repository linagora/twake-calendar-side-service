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

package com.linagora.calendar.storage.eventsearch;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.core.MailAddress;
import org.apache.james.vacation.api.AccountId;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Scopes;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.event.EventFields;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MemoryCalendarSearchService implements CalendarSearchService {
    private static final String DELIMITER = ":";

    public static Module MODULE = new AbstractModule() {
        @Override
        protected void configure() {
            bind(MemoryCalendarSearchService.class).in(Scopes.SINGLETON);
            bind(CalendarSearchService.class).to(MemoryCalendarSearchService.class);
        }
    };

    private final Table<AccountId, EventUid, CalendarEventsDTO> indexStore = Tables.synchronizedTable(HashBasedTable.create());

    @Override
    public Mono<Void> index(AccountId accountId, CalendarEvents calendarEvents) {
        return Mono.fromRunnable(() -> {
            CalendarEventsDTO calendarEventsDTO = Optional.ofNullable(indexStore.get(accountId, calendarEvents.eventUid()))
                .map(dto -> dto.replaceWith(calendarEvents.events()))
                .orElseGet(() -> CalendarEventsDTO.from(calendarEvents));

            indexStore.put(accountId, calendarEvents.eventUid(), calendarEventsDTO);
        });
    }

    @Override
    public Mono<Void> delete(AccountId accountId, EventUid eventUid) {
        return Mono.fromRunnable(() -> indexStore.remove(accountId, eventUid));
    }

    @Override
    public Flux<EventFields> search(AccountId accountId, EventSearchQuery query) {
        return Flux.fromIterable(indexStore.row(accountId).values())
            .flatMapIterable(CalendarEventsDTO::visibleEvents)
            .filter(event -> matchesQuery(event, query))
            .sort(Comparator.comparing(EventFields::start, Comparator.nullsLast(Comparator.reverseOrder())))
            .skip(query.offset())
            .take(query.limit());
    }

    @Override
    public Mono<Void> deleteAll(AccountId accountId) {
        return Mono.fromRunnable(() -> indexStore.rowKeySet().removeIf(accountId::equals));
    }

    private boolean matchesQuery(EventFields event, EventSearchQuery query) {
        if (!matchesQueryKeyword(event, query.query())) {
            return false;
        }
        return matchesOptionalFields(event, query);
    }

    private boolean matchesOptionalFields(EventFields event, EventSearchQuery query) {
        return query.calendars().map(calendarRefList -> matchesCalendarRef(event, calendarRefList)).orElse(true) &&
            query.organizers().map(organizers -> matchesOrganizers(event, organizers)).orElse(true) &&
            query.attendees().map(attendees -> matchesAttendees(event, attendees)).orElse(true);
    }

    private boolean matchesQueryKeyword(EventFields event, String keyword) {
        if (StringUtils.isEmpty(keyword)) {
            return true;
        }

        return Strings.CI.contains(event.summary(), keyword) ||
            Strings.CI.contains(event.description(), keyword) ||
            Strings.CI.contains(event.location(), keyword) ||
            Optional.ofNullable(event.organizer()).filter(matchesPerson(keyword)).isPresent() ||
            Optional.ofNullable(event.attendees()).orElse(List.of())
                .stream().anyMatch(matchesPerson(keyword));
    }

    private Predicate<EventFields.Person> matchesPerson(String queryText) {
        return person -> Strings.CI.contains(person.email().asString(), queryText)
            || Strings.CI.contains(person.cn(), queryText);
    }

    private boolean matchesCalendarRef(EventFields event, List<CalendarURL> calendarURLList) {
        return calendarURLList.stream()
            .anyMatch(ref -> Strings.CS.equals(ref.base().value(), event.calendarURL().base().value())
                && Strings.CS.equals(ref.calendarId().value(), event.calendarURL().calendarId().value()));
    }

    private boolean matchesOrganizers(EventFields event, List<MailAddress> organizers) {
        return organizers.stream()
            .anyMatch(organizer -> Objects.equals(organizer, event.organizer().email()));
    }

    private boolean matchesAttendees(EventFields event, List<MailAddress> attendees) {
        return event.attendees().stream()
            .anyMatch(attendee -> attendees.stream()
                .anyMatch(attendeeMail -> Objects.equals(attendeeMail, attendee.email())));
    }

    record CalendarEventsDTO(CalendarURL calendarURL,
                             HashMap<String, EventEntry> eventsByKey) {
        static final boolean DELETED = true;
        static final boolean SHOULD_REPLACE = true;

        record EventEntry(EventFields event, boolean deleted, Optional<Integer> lastSequence) {
            static EventEntry from(EventFields event) {
                return new EventEntry(event, !DELETED, event.sequence());
            }
        }

        static CalendarEventsDTO from(CalendarEvents calendarEvents) {
            HashMap<String, EventEntry> eventMap = new HashMap<>();
            calendarEvents.events()
                .forEach(event -> eventMap.put(eventKey(event), EventEntry.from(event)));
            return new CalendarEventsDTO(calendarEvents.calendarURL(), eventMap);
        }

        CalendarEventsDTO replaceWith(Set<EventFields> incomingEvents) {
            for (EventFields incomingEvent : incomingEvents) {
                String key = eventKey(incomingEvent);
                Optional<EventFields> existingEvent = Optional.ofNullable(eventsByKey.get(key))
                    .map(EventEntry::event);

                if (shouldReplace(existingEvent, incomingEvent)) {
                    eventsByKey.put(key, new EventEntry(incomingEvent, false, incomingEvent.sequence()));
                }
            }
            return this;
        }

        static boolean shouldReplace(Optional<EventFields> existing, EventFields incoming) {
            Optional<Integer> oldSeq = existing.flatMap(EventFields::sequence);
            Optional<Integer> newSeq = incoming.sequence();

            if (oldSeq.isEmpty()) {
                return SHOULD_REPLACE;
            }
            if (newSeq.isEmpty()) {
                return SHOULD_REPLACE;
            }
            return newSeq.get() > oldSeq.get();
        }

        List<EventFields> visibleEvents() {
            return eventsByKey.values().stream()
                .filter(entry -> !entry.deleted())
                .map(EventEntry::event)
                .toList();
        }

        private static String eventKey(EventFields eventFields) {
            Supplier<String> masterRecurrence = () -> {
                if (eventFields.isRecurrentMaster() == null) {
                    return "single";
                }
                if (eventFields.isRecurrentMaster()) {
                    return "master";
                }
                return "recurrence";
            };

            return String.join(DELIMITER,
                eventFields.uid().value(),
                masterRecurrence.get(),
                Optional.ofNullable(eventFields.start())
                    .map(Instant::toEpochMilli)
                    .map(String::valueOf).orElse(StringUtils.EMPTY));
        }
    }
}
