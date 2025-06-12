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
import java.util.Optional;

import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;

import com.linagora.calendar.webadmin.service.CalendarEventsReindexService;

public class CalendarEventsReindexTask implements Task {
    public record Details(Instant instant, long processedEventCount, long failedEventCount) implements TaskExecutionDetails.AdditionalInformation {
        @Override
        public Instant timestamp() {
            return instant;
        }
    }

    public static final TaskType REINDEX_CALENDAR_EVENTS = TaskType.of("reindex-calendar-events");

    private final CalendarEventsReindexService reindexService;
    private final RunningOptions runningOptions;
    private final CalendarEventsReindexService.Context context;

    public CalendarEventsReindexTask(CalendarEventsReindexService reindexService, RunningOptions runningOptions) {
        this.reindexService = reindexService;
        this.runningOptions = runningOptions;
        this.context = new CalendarEventsReindexService.Context();
    }

    @Override
    public Result run() {
        return reindexService.reindex(context, runningOptions.eventsPerSecond()).block();
    }

    @Override
    public TaskType type() {
        return REINDEX_CALENDAR_EVENTS;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        return Optional.of(new Details(Clock.systemUTC().instant(),
            context.snapshot().processedEventCount(),
            context.snapshot().failedEventCount()));
    }
}
