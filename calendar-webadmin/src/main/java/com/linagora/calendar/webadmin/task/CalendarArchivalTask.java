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

import org.apache.james.core.Username;
import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;

import com.linagora.calendar.webadmin.model.EventArchivalCriteria;
import com.linagora.calendar.webadmin.service.CalendarEventArchivalService;

public class CalendarArchivalTask implements Task {

    public record Details(Instant instant,
                          long archivedEventCount,
                          long failedEventCount,
                          Optional<Username> targetUser,
                          EventArchivalCriteria criteria) implements TaskExecutionDetails.AdditionalInformation {

        @Override
        public Instant timestamp() {
            return instant;
        }
    }

    public static final TaskType CALENDAR_ARCHIVAL = TaskType.of("calendar-archival");

    private final CalendarEventArchivalService archivalService;
    private final RunningOptions runningOptions;
    private final CalendarEventArchivalService.Context context;
    private final Optional<Username> targetUser;
    private final EventArchivalCriteria criteria;

    public CalendarArchivalTask(CalendarEventArchivalService archivalService,
                                RunningOptions runningOptions,
                                EventArchivalCriteria archivalCriteria,
                                Optional<Username> targetUser) {

        this.archivalService = archivalService;
        this.runningOptions = runningOptions;
        this.targetUser = targetUser;
        this.criteria = archivalCriteria;
        this.context = new CalendarEventArchivalService.Context();
    }

    @Override
    public Result run() throws InterruptedException {
        return targetUser
            .map(singleUser -> archivalService.archiveUser(singleUser, criteria, context, runningOptions.eventsPerSecond()))
            .orElse(archivalService.archive(criteria, context, runningOptions.eventsPerSecond()))
            .block();
    }

    @Override
    public TaskType type() {
        return CALENDAR_ARCHIVAL;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        return Optional.of(new Details(Clock.systemUTC().instant(),
            context.snapshot().success(),
            context.snapshot().failure(), targetUser, criteria));
    }
}
