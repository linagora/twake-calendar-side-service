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

import org.apache.james.task.Task;
import org.apache.james.task.TaskType;

import com.linagora.calendar.webadmin.service.CalendarEventsReindexService;

public class CalendarEventsReindexTask implements Task {
    public static final TaskType REINDEX_CALENDAR_EVENTS = TaskType.of("reindex-calendar-events");

    private final CalendarEventsReindexService reindexService;

    public CalendarEventsReindexTask(CalendarEventsReindexService reindexService) {
        this.reindexService = reindexService;
    }

    @Override
    public Result run() {
        try {
            reindexService.reindex().block();
            return Result.COMPLETED;
        } catch (Exception e) {
            return Result.PARTIAL;
        }
    }

    @Override
    public TaskType type() {
        return REINDEX_CALENDAR_EVENTS;
    }
}
