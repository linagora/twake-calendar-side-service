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

import org.apache.james.core.Username;
import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;

import com.linagora.calendar.dav.importer.EventToImport;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.webadmin.service.CalendarImportService;

public class CalendarImportTask implements Task {

    public record Details(Instant instant, String username, String calendarId,
                          long totalEventCount, long importedEventCount,
                          long failedEventCount) implements TaskExecutionDetails.AdditionalInformation {
        @Override
        public Instant timestamp() {
            return instant;
        }
    }

    public static final TaskType IMPORT_CALENDAR = TaskType.of("calendar-import");

    private final CalendarImportService importService;
    private final Username username;
    private final CalendarURL calendarURL;
    private final List<EventToImport> events;
    private final CalendarImportService.Context context;

    public CalendarImportTask(CalendarImportService importService, Username username,
                              CalendarURL calendarURL, List<EventToImport> events) {
        this.importService = importService;
        this.username = username;
        this.calendarURL = calendarURL;
        this.events = events;
        this.context = new CalendarImportService.Context();
    }

    @Override
    public Result run() {
        return importService.importEvents(username, calendarURL, events, context).block();
    }

    @Override
    public TaskType type() {
        return IMPORT_CALENDAR;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        CalendarImportService.Context.Snapshot snapshot = context.snapshot();

        return Optional.of(new Details(Clock.systemUTC().instant(),
            username.asString(),
            calendarURL.calendarId().value(),
            events.size(),
            snapshot.importedCount(),
            snapshot.failedCount()));
    }
}
