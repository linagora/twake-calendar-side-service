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


package com.linagora.calendar.webadmin.task;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.james.core.Domain;
import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;

import com.linagora.calendar.dav.importer.EventToImport;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.webadmin.service.CalendarImportService;

/**
 * Imports events into a calendar owned by a domain rather than by a user: a team calendar or the calendar
 * of a resource. The DAV writes are thus performed with the technical token of that domain.
 */
public class DomainCalendarImportTask implements Task {

    /**
     * Which kind of domain scoped calendar is being filled. Recorded in the task details: team calendars and
     * resources share the same calendar layout on the DAV server, their identifiers alone do not tell them apart.
     */
    public enum CalendarType {
        TEAM_CALENDAR("team-calendar"),
        RESOURCE("resource");

        private final String value;

        CalendarType(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public record Details(Instant instant, String domain, String calendarType, String calendarId,
                          long totalEventCount, long importedEventCount,
                          long failedEventCount) implements TaskExecutionDetails.AdditionalInformation {
        @Override
        public Instant timestamp() {
            return instant;
        }
    }

    public static final TaskType IMPORT_DOMAIN_CALENDAR = TaskType.of("domain-calendar-import");

    private final CalendarImportService importService;
    private final Domain domain;
    private final OpenPaaSId domainId;
    private final CalendarType calendarType;
    private final CalendarURL calendarURL;
    private final List<EventToImport> events;
    private final CalendarImportService.Context context;

    public DomainCalendarImportTask(CalendarImportService importService, Domain domain, OpenPaaSId domainId,
                                    CalendarType calendarType, CalendarURL calendarURL, List<EventToImport> events) {
        this.importService = importService;
        this.domain = domain;
        this.domainId = domainId;
        this.calendarType = calendarType;
        this.calendarURL = calendarURL;
        this.events = events;
        this.context = new CalendarImportService.Context();
    }

    @Override
    public Result run() {
        return importService.importEvents(domainId, calendarURL, events, context).block();
    }

    @Override
    public TaskType type() {
        return IMPORT_DOMAIN_CALENDAR;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        CalendarImportService.Context.Snapshot snapshot = context.snapshot();

        return Optional.of(new Details(Clock.systemUTC().instant(),
            domain.asString(),
            calendarType.value(),
            calendarURL.calendarId().value(),
            events.size(),
            snapshot.importedCount(),
            snapshot.failedCount()));
    }
}
