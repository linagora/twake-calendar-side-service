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

package com.linagora.calendar.amqp;

import static com.linagora.calendar.storage.configuration.resolver.AlarmSettingReader.ALARM_SETTING_IDENTIFIER;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavCalendarObject;
import com.linagora.calendar.smtp.EventEmailFilter;
import com.linagora.calendar.smtp.MailUsernames;
import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.AlarmEventDAO;
import com.linagora.calendar.storage.AlarmEventFactory;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.event.AlarmInstantFactory;
import com.linagora.calendar.storage.event.AlarmInstantFactory.AlarmInstant;
import com.linagora.calendar.storage.eventsearch.EventUid;

import net.fortuna.ical4j.model.Calendar;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class EventAlarmHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventAlarmHandler.class);

    private final AlarmInstantFactory alarmInstantFactory;
    private final AlarmEventDAO alarmEventDAO;
    private final CalDavClient calDavClient;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final SettingsBasedResolver settingsResolver;
    private final AlarmEventFactory alarmEventFactory;
    private final EventEmailFilter eventEmailFilter;

    @Inject
    @Singleton
    public EventAlarmHandler(AlarmInstantFactory alarmInstantFactory,
                             AlarmEventDAO alarmEventDAO,
                             CalDavClient calDavClient,
                             OpenPaaSUserDAO openPaaSUserDAO,
                             @Named("alarm") SettingsBasedResolver settingsResolver,
                             AlarmEventFactory alarmEventFactory,
                             EventEmailFilter eventEmailFilter) {
        this.alarmInstantFactory = alarmInstantFactory;
        this.alarmEventDAO = alarmEventDAO;
        this.calDavClient = calDavClient;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.settingsResolver = settingsResolver;
        this.alarmEventFactory = alarmEventFactory;
        this.eventEmailFilter = eventEmailFilter;
    }

    public Mono<Void> handleCreate(CalendarAlarmMessageDTO alarmMessageDTO) {
        if (!alarmMessageDTO.hasVALARMComponent()) {
            return Mono.empty();
        }
        return handleCreateOrUpdate(alarmMessageDTO);
    }

    public Mono<Void> handleCreateOrUpdate(CalendarAlarmMessageDTO alarmMessageDTO) {
        return openPaaSUserDAO.retrieve(alarmMessageDTO.extractCalendarURL().base())
            .filterWhen(openPaaSUser -> isUserAlarmEnabled(openPaaSUser.username()))
            .flatMap(openPaaSUser -> processCreateOrUpdate(openPaaSUser.username(), alarmMessageDTO));
    }

    private Mono<Void> processCreateOrUpdate(Username username, CalendarAlarmMessageDTO alarmMessageDTO) {

        Mono<Calendar> calendarMono = alarmMessageDTO.rawEvent()
            .map(CalendarUtil::parseIcs)
            .map(Mono::just)
            .orElseGet(() -> calDavClient.fetchCalendarEvent(username, URI.create(alarmMessageDTO.eventPath()))
                .map(DavCalendarObject::calendarData));

        return calendarMono.flatMap(ics -> applyNextAlarmDecision(username, ics, alarmMessageDTO))
            .onErrorResume(error -> {
                LOGGER.error("Failed to create/update alarm for {} at {}", username.asString(), alarmMessageDTO.eventPath(), error);
                return Mono.empty();
            });
    }

    private Mono<Void> applyNextAlarmDecision(Username username, Calendar eventCalendar, CalendarAlarmMessageDTO alarmMessageDTO) {
        return Mono.just(alarmInstantFactory.computeNextAlarmInstant(eventCalendar, username))
            .flatMap(maybeNextAlarmInstant -> {
                if (maybeNextAlarmInstant.isPresent()) {
                    LOGGER.debug("Next alarm found for {} at {}", username.asString(), alarmMessageDTO.eventPath());
                    return upsertUpcomingAlarmRequest(username, eventCalendar, maybeNextAlarmInstant.get(), alarmMessageDTO.eventPath());
                } else {
                    LOGGER.debug("No upcoming alarm found for {} at {}", username.asString(), alarmMessageDTO.eventPath());
                    return doDeleteAlarmEvent(username, alarmMessageDTO);
                }
            });
    }

    private Mono<Void> upsertUpcomingAlarmRequest(Username username, Calendar calendarEvent, AlarmInstant nextAlarmInstant, String eventPath) {
        return Flux.fromIterable(alarmEventFactory.buildAlarmEvent(username,
                nextAlarmInstant.recipients().stream().filter(eventEmailFilter::shouldProcess).toList(),
                calendarEvent,
                nextAlarmInstant,
                eventPath))
            .flatMap(alarmEvent -> upsertAlarmEvent(username, alarmEvent))
            .then();
    }

    /**
     * UIDs are only unique within a calendar home: the recipient's own object always replaces their alarm, another user's
     * object only replaces an alarm scheduled from its own calendar home. The DAO checks that condition atomically.
     */
    private Mono<AlarmEvent> upsertAlarmEvent(Username username, AlarmEvent alarmEvent) {
        if (isOwnedByItsRecipient(username, alarmEvent)) {
            LOGGER.debug("Upserting the alarm event {} of its recipient", alarmEvent.eventUid().value());
            return alarmEventDAO.update(alarmEvent).thenReturn(alarmEvent);
        }
        return Mono.justOrEmpty(calendarHome(alarmEvent.eventPath()))
            .flatMap(calendarHome -> alarmEventDAO.upsertIfReplaceableFrom(alarmEvent, calendarHome))
            .defaultIfEmpty(false)
            .flatMap(written -> {
                if (!written) {
                    LOGGER.warn("Not replacing the alarm of {} for {}: it is scheduled from another calendar home than {}",
                        alarmEvent.recipient().asString(), alarmEvent.eventUid().value(), alarmEvent.eventPath());
                    return Mono.empty();
                }
                return Mono.just(alarmEvent);
            });
    }

    private static boolean isOwnedByItsRecipient(Username owner, AlarmEvent alarmEvent) {
        return MailUsernames.asUsername(alarmEvent.recipient())
            .map(recipient -> recipient.asString().equalsIgnoreCase(owner.asString()))
            .orElse(false);
    }

    private static Optional<OpenPaaSId> calendarHome(String eventPath) {
        try {
            return Optional.of(EventFieldConverter.extractCalendarURL(eventPath).base());
        } catch (CalendarEventDeserializeException e) {
            return Optional.empty();
        }
    }

    public Mono<Void> handleDelete(CalendarAlarmMessageDTO alarmMessageDTO) {
        return openPaaSUserDAO.retrieve(alarmMessageDTO.extractCalendarURL().base())
            .flatMap(openPaaSUser -> doDeleteAlarmEvent(openPaaSUser.username(), alarmMessageDTO));
    }

    /**
     * Alarms scheduled by a calendar object may target recipients that are not attendees of the event
     * (VALARM ATTENDEE delegation), thus deletion is scoped to the calendar object rather than to its owner.
     */
    private Mono<Void> doDeleteAlarmEvent(Username username, CalendarAlarmMessageDTO alarmMessageDTO) {
        String eventPath = alarmMessageDTO.eventPath();
        return Mono.fromCallable(() -> extractEventUid(alarmMessageDTO))
            .flatMap(eventUid -> alarmEventDAO.deleteByEventPath(eventUid, eventPath)
                .doOnSuccess(unused -> LOGGER.debug("Deleted alarm events of {} for {}", eventPath, username.asString()))
                .onErrorResume(error -> {
                    LOGGER.error("Failed to delete alarm events of {} for {}", eventPath, username.asString(), error);
                    return Mono.empty();
                }));
    }

    private EventUid extractEventUid(CalendarAlarmMessageDTO alarmMessageDTO) {
        return EventFieldConverter.extractVEventProperties(alarmMessageDTO.calendarEvent(), EventProperty.UID_PROPERTY)
            .stream().flatMap(List::stream)
            .filter(property -> property instanceof EventProperty.EventUidProperty)
            .map(property -> ((EventProperty.EventUidProperty) property).getEventUid())
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Event UID not found in the calendar event"));
    }

    private Mono<Boolean> isUserAlarmEnabled(Username user) {
        return settingsResolver.resolveOrDefault(user)
            .flatMap(settings -> Mono.justOrEmpty(settings.get(ALARM_SETTING_IDENTIFIER, Boolean.class)));
    }
}