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
import static com.linagora.calendar.storage.configuration.resolver.AlarmSettingReader.ENABLE_ALARM;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.AlarmEventDAO;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.configuration.resolver.AlarmSettingReader;
import com.linagora.calendar.storage.configuration.resolver.ConfigurationResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.event.AlarmInstantFactory;
import com.linagora.calendar.storage.event.AlarmInstantFactory.AlarmInstant;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.linagora.calendar.storage.eventsearch.EventUid;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.property.DateProperty;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class EventAlarmHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventAlarmHandler.class);

    private final AlarmInstantFactory alarmInstantFactory;
    private final AlarmEventDAO alarmEventDAO;
    private final CalDavClient calDavClient;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final SettingsBasedResolver settingsBasedResolver;
    private final SimpleSessionProvider sessionProvider;
    private final EventEmailFilter eventEmailFilter;

    @Inject
    @Singleton
    public EventAlarmHandler(AlarmInstantFactory alarmInstantFactory,
                             AlarmEventDAO alarmEventDAO,
                             CalDavClient calDavClient,
                             OpenPaaSUserDAO openPaaSUserDAO,
                             ConfigurationResolver configurationResolver,
                             SimpleSessionProvider sessionProvider,
                             EventEmailFilter eventEmailFilter) {
        this(alarmInstantFactory, alarmEventDAO, calDavClient, openPaaSUserDAO,
            SettingsBasedResolver.of(configurationResolver, Set.of(new AlarmSettingReader())),
            sessionProvider, eventEmailFilter);
    }

    public EventAlarmHandler(AlarmInstantFactory alarmInstantFactory,
                             AlarmEventDAO alarmEventDAO,
                             CalDavClient calDavClient,
                             OpenPaaSUserDAO openPaaSUserDAO,
                             SettingsBasedResolver settingsBasedResolver,
                             SimpleSessionProvider sessionProvider,
                             EventEmailFilter eventEmailFilter) {
        this.alarmInstantFactory = alarmInstantFactory;
        this.alarmEventDAO = alarmEventDAO;
        this.calDavClient = calDavClient;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.settingsBasedResolver = settingsBasedResolver;
        this.sessionProvider = sessionProvider;
        this.eventEmailFilter = eventEmailFilter;
    }

    public Mono<Void> handleCreateOrUpdate(CalendarAlarmMessageDTO alarmMessageDTO) {
        return openPaaSUserDAO.retrieve(alarmMessageDTO.extractCalendarURL().base())
            .filterWhen(openPaaSUser -> isUserAlarmEnabled(openPaaSUser.username()))
            .flatMap(openPaaSUser -> processCreateOrUpdate(openPaaSUser.username(), alarmMessageDTO));
    }

    private Mono<Void> processCreateOrUpdate(Username username, CalendarAlarmMessageDTO alarmMessageDTO) {
        return calDavClient.fetchCalendarEvent(username, URI.create(alarmMessageDTO.eventPath()))
            .flatMap(davCalendarObject -> applyNextAlarmDecision(username, davCalendarObject.calendarData(), alarmMessageDTO))
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
                    return upsertUpcomingAlarmRequest(username, eventCalendar, maybeNextAlarmInstant.get());
                } else {
                    LOGGER.debug("No upcoming alarm found for {} at {}", username.asString(), alarmMessageDTO.eventPath());
                    return doDeleteAlarmEvent(username, extractEventUid(alarmMessageDTO));
                }
            });
    }

    private Mono<Void> upsertUpcomingAlarmRequest(Username username, Calendar eventCalendar, AlarmInstant nextAlarmInstant) {
        return buildAlarmEvent(eventCalendar, nextAlarmInstant)
            .flatMap(alarmEvent -> upsertAlarmEvent(username, alarmEvent))
            .then();
    }

    private Mono<AlarmEvent> upsertAlarmEvent(Username username, AlarmEvent alarmEvent) {
        return alarmEventDAO.find(alarmEvent.eventUid(), Throwing.supplier(username::asMailAddress).get())
            .flatMap(existing -> {
                LOGGER.debug("Updating existing alarm event: {}", alarmEvent.eventUid().value());
                return alarmEventDAO.update(alarmEvent).thenReturn(alarmEvent);
            })
            .switchIfEmpty(Mono.defer(() -> {
                LOGGER.debug("Creating new alarm event: {}", alarmEvent.eventUid().value());
                return alarmEventDAO.create(alarmEvent).thenReturn(alarmEvent);
            }));
    }

    private Flux<AlarmEvent> buildAlarmEvent(Calendar eventCalendar, AlarmInstant nextAlarmInstant) {
        boolean recurringEvent = EventParseUtils.isRecurringEvent(eventCalendar);
        EventUid eventUid = new EventUid(EventParseUtils.extractEventUid(eventCalendar));
        Optional<String> recurrenceIdValue = nextAlarmInstant.recurrenceId().map(DateProperty::getValue);
        String eventCalendarString = eventCalendar.toString();

        return Flux.fromIterable(nextAlarmInstant.recipients())
            .filter(eventEmailFilter::shouldProcess)
            .map(recipient -> new AlarmEvent(
                eventUid,
                nextAlarmInstant.alarmTime(),
                nextAlarmInstant.eventStartTime(),
                recurringEvent,
                recurrenceIdValue,
                recipient, eventCalendarString));
    }

    private boolean hasDifferentAlarmTimes(AlarmEvent existing, AlarmEvent newEvent) {
        return !existing.alarmTime().equals(newEvent.alarmTime())
            || !existing.eventStartTime().equals(newEvent.eventStartTime());
    }

    public Mono<Void> handleDelete(CalendarAlarmMessageDTO alarmMessageDTO) {
        return openPaaSUserDAO.retrieve(alarmMessageDTO.extractCalendarURL().base())
            .flatMap(openPaaSUser -> handleDelete(openPaaSUser.username(), alarmMessageDTO));
    }

    private Mono<Void> handleDelete(Username username, CalendarAlarmMessageDTO message) {
        return Mono.fromCallable(() -> extractEventUid(message))
            .flatMap(eventUid -> doDeleteAlarmEvent(username, eventUid));
    }

    private Mono<Void> doDeleteAlarmEvent(Username username, EventUid eventUid) {
        return alarmEventDAO.delete(eventUid, Throwing.supplier(username::asMailAddress).get())
            .doOnSuccess(unused -> LOGGER.debug("Deleted alarm event for {} with UID {}", username.asString(), eventUid.value()))
            .onErrorResume(error -> {
                LOGGER.error("Failed to delete alarm event for {} with UID {}", username.asString(), eventUid.value(), error);
                return Mono.empty();
            });
    }

    private EventUid extractEventUid(CalendarAlarmMessageDTO alarmMessageDTO) {
        return EventFieldConverter.extractVEventProperties(alarmMessageDTO.calendarEvent())
            .stream().flatMap(List::stream)
            .filter(property -> property instanceof EventProperty.EventUidProperty)
            .map(property -> ((EventProperty.EventUidProperty) property).getEventUid())
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Event UID not found in the calendar event"));
    }

    private Mono<Boolean> isUserAlarmEnabled(Username user) {
        return settingsBasedResolver.readSavedSettings(sessionProvider.createSession(user))
            .flatMap(settings -> Mono.justOrEmpty(settings.get(ALARM_SETTING_IDENTIFIER, Boolean.class)))
            .onErrorResume(throwable -> {
                LOGGER.error("Failed to read alarm settings for {} ", user.asString(), throwable);
                return Mono.just(ENABLE_ALARM);
            }).defaultIfEmpty(ENABLE_ALARM);
    }
}