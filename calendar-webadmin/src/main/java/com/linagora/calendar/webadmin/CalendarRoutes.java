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

package com.linagora.calendar.webadmin;

import static com.linagora.calendar.webadmin.task.RunningOptions.DEFAULT_EVENTS_PER_SECOND;

import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.tasks.TaskFromRequestRegistry;
import org.apache.james.webadmin.tasks.TaskRegistrationKey;
import org.apache.james.webadmin.utils.JsonTransformer;

import com.linagora.calendar.webadmin.service.AlarmScheduleService;
import com.linagora.calendar.webadmin.service.CalendarEventsReindexService;
import com.linagora.calendar.webadmin.task.AlarmScheduleTask;
import com.linagora.calendar.webadmin.task.CalendarEventsReindexTask;
import com.linagora.calendar.webadmin.task.RunningOptions;

import spark.Request;
import spark.Route;
import spark.Service;

public class CalendarRoutes implements Routes {

    public static class CalendarEventsReindexRequestToTask extends TaskFromRequestRegistry.TaskRegistration {
        public static final TaskRegistrationKey TASK_NAME = TaskRegistrationKey.of("reindex");

        private static final String EVENTS_PER_SECOND = "eventsPerSecond";

        @Inject
        public CalendarEventsReindexRequestToTask(CalendarEventsReindexService reindexService) {
            super(TASK_NAME, request -> {
                int usersPerSecond = extractEventsPerSecond(request);
                return new CalendarEventsReindexTask(reindexService, RunningOptions.of(usersPerSecond));
            });
        }

        private static Integer extractEventsPerSecond(Request request) {
            try {
                return Optional.ofNullable(request.queryParams(EVENTS_PER_SECOND))
                    .map(Integer::parseInt)
                    .orElse(DEFAULT_EVENTS_PER_SECOND);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(String.format("Illegal value supplied for query parameter '%s', expecting a " +
                    "strictly positive optional integer", EVENTS_PER_SECOND), e);
            }
        }
    }

    public static class AlarmScheduleRequestToTask extends TaskFromRequestRegistry.TaskRegistration {
        public static final TaskRegistrationKey TASK_NAME = TaskRegistrationKey.of("scheduleAlarms");

        private static final String EVENTS_PER_SECOND = "eventsPerSecond";

        @Inject
        public AlarmScheduleRequestToTask(AlarmScheduleService alarmScheduleService) {
            super(TASK_NAME, request -> {
                int eventsPerSecond = extractEventsPerSecond(request);
                return new AlarmScheduleTask(alarmScheduleService, RunningOptions.of(eventsPerSecond));
            });
        }

        private static Integer extractEventsPerSecond(Request request) {
            try {
                return Optional.ofNullable(request.queryParams(EVENTS_PER_SECOND))
                    .map(Integer::parseInt)
                    .orElse(DEFAULT_EVENTS_PER_SECOND);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(String.format("Illegal value supplied for query parameter '%s', expecting a " +
                    "strictly positive optional integer", EVENTS_PER_SECOND), e);
            }
        }
    }

    public static final String BASE_PATH = "/calendars";

    private final JsonTransformer jsonTransformer;
    private final TaskManager taskManager;
    private final Set<TaskFromRequestRegistry.TaskRegistration> taskRegistrations;

    @Inject
    public CalendarRoutes(JsonTransformer jsonTransformer, TaskManager taskManager, Set<TaskFromRequestRegistry.TaskRegistration> taskRegistrations) {
        this.jsonTransformer = jsonTransformer;
        this.taskManager = taskManager;
        this.taskRegistrations = taskRegistrations;
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        defineReindexCalendarSearchTaskRoute()
            .ifPresent(route -> service.post(BASE_PATH, route, jsonTransformer));
    }

    public Optional<Route> defineReindexCalendarSearchTaskRoute() {
        return TaskFromRequestRegistry.builder()
            .parameterName("task")
            .registrations(taskRegistrations)
            .buildAsRouteOptional(taskManager);
    }

}
