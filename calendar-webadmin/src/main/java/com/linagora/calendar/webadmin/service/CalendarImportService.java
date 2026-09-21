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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Imports the events of an ICS document into a calendar of the DAV server.
 */
public class CalendarImportService {

    /**
     * A single calendar object, as stored on the DAV server: the VEVENTs sharing a given UID - a master event
     * and its recurrence overrides - alongside the calendar level properties and time zones they rely on.
     */
    public record EventToImport(String resourceName, byte[] ics) {

        public static List<EventToImport> parse(byte[] icsPayload) {
            Calendar calendar = CalendarUtil.parseIcs(icsPayload);

            return EventParseUtils.groupByUid(calendar)
                .entrySet()
                .stream()
                .map(entry -> new EventToImport(DavResourceName.fromUid(entry.getKey()),
                    CalendarUtil.withVEvents(calendar, entry.getValue()).toString().getBytes(StandardCharsets.UTF_8)))
                .toList();
        }
    }

    public static class Context {
        public record Snapshot(long importedCount, long failedCount) {
            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this)
                    .add("importedCount", importedCount)
                    .add("failedCount", failedCount)
                    .toString();
            }
        }

        private final AtomicLong importedCount = new AtomicLong();
        private final AtomicLong failedCount = new AtomicLong();

        public Snapshot snapshot() {
            return new Snapshot(importedCount.get(), failedCount.get());
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarImportService.class);

    private final CalDavClient calDavClient;

    @Inject
    public CalendarImportService(CalDavClient calDavClient) {
        this.calDavClient = calDavClient;
    }

    public Mono<Task.Result> importEvents(Username username, CalendarURL calendarURL,
                                          List<EventToImport> events, Context context) {
        return Flux.fromIterable(events)
            .concatMap(event -> importEvent(username, calendarURL, event, context))
            .reduce(Task.Result.COMPLETED, Task::combine);
    }

    private Mono<Task.Result> importEvent(Username username, CalendarURL calendarURL,
                                          EventToImport event, Context context) {
        return calDavClient.importCalendar(calendarURL, event.resourceName(), username, event.ics())
            .doOnSuccess(any -> context.importedCount.incrementAndGet())
            .thenReturn(Task.Result.COMPLETED)
            .onErrorResume(error -> {
                LOGGER.warn("Importing event {} into calendar {} of user {} failed",
                    event.resourceName(), calendarURL.asUri().toASCIIString(), username.asString(), error);
                context.failedCount.incrementAndGet();
                return Mono.just(Task.Result.PARTIAL);
            });
    }
}
