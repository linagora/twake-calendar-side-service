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

import com.google.common.base.Preconditions;
import com.linagora.calendar.webadmin.service.CalendarEventsReindexService;

public class CalendarEventsReindexTask implements Task {
    public record Details(Instant instant, long processedEventCount, long failedEventCount) implements TaskExecutionDetails.AdditionalInformation {
        @Override
        public Instant timestamp() {
            return instant;
        }
    }

    public record RunningOptions(int eventsPerSecond,
                                 int calendarsConcurrency) {
        public static final int DEFAULT_EVENTS_PER_SECOND = com.linagora.calendar.webadmin.task.RunningOptions.DEFAULT_EVENTS_PER_SECOND;
        public static final int DEFAULT_CALENDARS_CONCURRENCY = 1;

        public static final RunningOptions DEFAULT = of(
            DEFAULT_EVENTS_PER_SECOND,
            DEFAULT_CALENDARS_CONCURRENCY);

        public RunningOptions {
            Preconditions.checkArgument(eventsPerSecond > 0, "eventsPerSecond must be strictly positive");
            Preconditions.checkArgument(calendarsConcurrency > 0, "calendarsConcurrency must be strictly positive");
        }

        public static RunningOptions of(int eventsPerSecond, int calendarsConcurrency) {
            return new RunningOptions(eventsPerSecond, calendarsConcurrency);
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
        return reindexService.reindex(context, runningOptions).block();
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
