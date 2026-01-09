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

package com.linagora.calendar.webadmin.service;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.apache.james.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavClient.NewCalendar;
import com.linagora.calendar.dav.dto.CalendarReportXmlResponse;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.webadmin.model.EventArchivalCriteria;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class CalendarEventArchivalService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarEventArchivalService.class);

    private static final String ARCHIVAL_NAME = "Archival";

    private static final Supplier<NewCalendar> ARCHIVAL_CALENDAR_SUPPLIER =
        () -> new NewCalendar(UUID.randomUUID().toString(), ARCHIVAL_NAME, "#8E8E93",
            "Archived events. Events moved here are no longer active.");

    private final CalDavClient calDavClient;

    @Inject
    public CalendarEventArchivalService(CalDavClient calDavClient) {
        this.calDavClient = calDavClient;
    }

    public Mono<Task.Result> archiveUser(OpenPaaSUser user, EventArchivalCriteria criteria) {
        LOGGER.info("Starting event archival for user {} with criteria {}",
            user.username().asString(), criteria);

        CalendarURL sourceCalendar = CalendarURL.from(user.id());

        return resolveArchivalCalendar(user)
            .flatMap(archivalCalendar -> {
                LOGGER.info("Archiving events from default calendar {} to archival calendar {} for user {}",
                    sourceCalendar.asUri(), archivalCalendar.asUri(), user.username().asString());
                return archiveFromCalendar(user, sourceCalendar, archivalCalendar, criteria);
            })
            .thenReturn(Task.Result.COMPLETED)
            .onErrorResume(e -> {
                LOGGER.error("Event archival failed for user {}", user.username().asString(), e);
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Mono<CalendarURL> resolveArchivalCalendar(OpenPaaSUser user) {
        return calDavClient.findUserCalendars(user.username(), user.id(), Map.of("personal", "true"))
            .flatMap(calendarList -> Mono.justOrEmpty(calendarList.findCalendarByName("Archival")))
            .switchIfEmpty(Mono.defer(() -> {
                LOGGER.info("Archival calendar not found for user {}, creating it", user.username().asString());
                NewCalendar newArchivalCalendar = ARCHIVAL_CALENDAR_SUPPLIER.get();
                return calDavClient.createNewCalendar(user.username(), user.id(), newArchivalCalendar)
                    .thenReturn(new CalendarURL(user.id(), new OpenPaaSId(newArchivalCalendar.id())));

            }));
    }

    private Mono<Void> archiveFromCalendar(OpenPaaSUser user,
                                           CalendarURL sourceCalendar,
                                           CalendarURL archivalCalendar,
                                           EventArchivalCriteria criteria) {
        return calDavClient.calendarQueryReportXml(
                user.username(),
                sourceCalendar,
                criteria.toCalendarQuery())
            .flatMapMany(response -> Flux.fromIterable(response.extractCalendarObjects()))
            .filter(calendarObject -> shouldArchive(calendarObject, criteria))
            .flatMap(calendarObject -> {
                String eventPathId = calendarObject.eventPathId();
                return calDavClient.importCalendar(archivalCalendar, eventPathId, user.username(), calendarObject.calendarDataAsBytes())
                    .then(calDavClient.deleteCalendarEvent(user.username(), sourceCalendar, eventPathId))
                    .onErrorResume(e -> {
                        LOGGER.warn("Failed to archive calendar object {} for user {} from calendar {}",
                            calendarObject.href(), user.username().asString(), sourceCalendar.asUri().toASCIIString(), e);
                        return Mono.empty();
                    });
            })
            .then();
    }

    private boolean shouldArchive(CalendarReportXmlResponse.CalendarObject calendarObject,
                                  EventArchivalCriteria criteria) {
        // Post-filter hook for validating DAV response.
        // Intentionally permissive for now; may evolve later.
        return true;
    }
}