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

import static com.linagora.calendar.webadmin.CalendarRoutesModule.USER_CALENDAR_TASKS_KEY;
import static com.linagora.calendar.webadmin.EventArchivalCriteriaRequestParser.extractEventArchivalCriteria;
import static com.linagora.calendar.webadmin.task.CalendarEventsReindexTask.RunningOptions.DEFAULT_CALENDARS_CONCURRENCY;
import static com.linagora.calendar.webadmin.task.RunningOptions.DEFAULT_EVENTS_PER_SECOND;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.core.Username;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.tasks.TaskFromRequest;
import org.apache.james.webadmin.tasks.TaskFromRequestRegistry;
import org.apache.james.webadmin.tasks.TaskRegistrationKey;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.webadmin.model.EventArchivalCriteria;
import com.linagora.calendar.webadmin.service.AlarmScheduleService;
import com.linagora.calendar.webadmin.service.CalendarEventArchivalService;
import com.linagora.calendar.webadmin.service.CalendarEventsReindexService;
import com.linagora.calendar.webadmin.task.AlarmScheduleTask;
import com.linagora.calendar.webadmin.task.CalendarArchivalTask;
import com.linagora.calendar.webadmin.task.CalendarEventsReindexTask;
import com.linagora.calendar.webadmin.task.RunningOptions;

import spark.Request;
import spark.Route;
import spark.Service;

public class CalendarRoutes implements Routes {
    private static final String EVENTS_PER_SECOND_PARAMETER = "eventsPerSecond";
    private static final String CALENDARS_CONCURRENCY_PARAMETER = "calendarsConcurrency";
    private static final String TASK_PARAMETER = "task";

    public static class CalendarEventsReindexRequestToTask extends TaskFromRequestRegistry.TaskRegistration {
        public static final TaskRegistrationKey TASK_NAME = TaskRegistrationKey.of("reindex");

        @Inject
        public CalendarEventsReindexRequestToTask(CalendarEventsReindexService reindexService) {
            super(TASK_NAME, request -> {
                int eventsPerSecond = extractEventsPerSecond(request);
                int calendarsConcurrency = extractCalendarsConcurrency(request);
                return new CalendarEventsReindexTask(reindexService,
                    CalendarEventsReindexTask.RunningOptions.of(eventsPerSecond, calendarsConcurrency));
            });
        }
    }

    public static class AlarmScheduleRequestToTask extends TaskFromRequestRegistry.TaskRegistration {
        public static final TaskRegistrationKey TASK_NAME = TaskRegistrationKey.of("scheduleAlarms");

        @Inject
        public AlarmScheduleRequestToTask(AlarmScheduleService alarmScheduleService) {
            super(TASK_NAME, request -> {
                int eventsPerSecond = extractEventsPerSecond(request);
                return new AlarmScheduleTask(alarmScheduleService, RunningOptions.of(eventsPerSecond));
            });
        }
    }

    public static class ArchiveRequestToTask extends TaskFromRequestRegistry.TaskRegistration {
        public static final TaskRegistrationKey TASK_NAME = TaskRegistrationKey.of("archive");

        @Inject
        public ArchiveRequestToTask(CalendarEventArchivalService archivalService, Clock clock) {
            super(TASK_NAME, request -> {
                int eventsPerSecond = extractEventsPerSecond(request);
                EventArchivalCriteria archivalCriteria = extractEventArchivalCriteria(request, clock);
                return new CalendarArchivalTask(archivalService, RunningOptions.of(eventsPerSecond),
                    archivalCriteria, Optional.empty());
            });
        }
    }

    public static class UserArchiveRequestToTask extends TaskFromRequestRegistry.TaskRegistration {
        public static final TaskRegistrationKey TASK_NAME = TaskRegistrationKey.of("archive");

        @Inject
        public UserArchiveRequestToTask(CalendarEventArchivalService archivalService, Clock clock, OpenPaaSUserDAO userDAO) {
            super(TASK_NAME, getTaskFromRequest(archivalService, clock, userDAO));
        }

        private static TaskFromRequest getTaskFromRequest(CalendarEventArchivalService archivalService, Clock clock, OpenPaaSUserDAO userDAO) {
            return request -> {
                int eventsPerSecond = extractEventsPerSecond(request);
                EventArchivalCriteria criteria = extractEventArchivalCriteria(request, clock);

                OpenPaaSUser openPaaSUser = Optional.ofNullable(request.params(USER_PARAM))
                    .flatMap(rawValue -> userDAO.retrieve(Username.of(rawValue))
                        .blockOptional())
                    .orElseThrow(() -> ErrorResponder.builder()
                        .statusCode(HttpStatus.NOT_FOUND_404)
                        .type(ErrorResponder.ErrorType.NOT_FOUND)
                        .message("User does not exist")
                        .haltError());
                return new CalendarArchivalTask(archivalService, RunningOptions.of(eventsPerSecond), criteria, Optional.of(openPaaSUser.username()));
            };
        }
    }

    public static final String BASE_PATH = "/calendars";
    public static final String USER_PARAM = ":userName";
    public static final String USER_PATH = BASE_PATH + "/" + USER_PARAM;

    private final JsonTransformer jsonTransformer;
    private final TaskManager taskManager;
    private final Set<TaskFromRequestRegistry.TaskRegistration> taskRegistrations;
    private final Set<TaskFromRequestRegistry.TaskRegistration> userTaskRegistrations;

    @Inject
    public CalendarRoutes(JsonTransformer jsonTransformer, TaskManager taskManager,
                          Set<TaskFromRequestRegistry.TaskRegistration> taskRegistrations,
                          @Named(USER_CALENDAR_TASKS_KEY) Set<TaskFromRequestRegistry.TaskRegistration> userTaskRegistrations) {
        this.jsonTransformer = jsonTransformer;
        this.taskManager = taskManager;
        this.taskRegistrations = taskRegistrations;
        this.userTaskRegistrations = userTaskRegistrations;
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        defineReindexCalendarSearchTaskRoute()
            .ifPresent(route -> service.post(BASE_PATH, route, jsonTransformer));

        defineUserCalendarTaskRoute()
            .ifPresent(route -> service.post(USER_PATH, route, jsonTransformer));
    }

    public Optional<Route> defineReindexCalendarSearchTaskRoute() {
        return TaskFromRequestRegistry.builder()
            .parameterName(TASK_PARAMETER)
            .registrations(taskRegistrations)
            .buildAsRouteOptional(taskManager);
    }

    public Optional<Route> defineUserCalendarTaskRoute() {
        return TaskFromRequestRegistry.builder()
            .parameterName(TASK_PARAMETER)
            .registrations(userTaskRegistrations)
            .buildAsRouteOptional(taskManager);
    }

    private static Integer extractEventsPerSecond(Request request) {
        return extractPositiveIntegerParameter(request, EVENTS_PER_SECOND_PARAMETER, DEFAULT_EVENTS_PER_SECOND);
    }

    private static Integer extractCalendarsConcurrency(Request request) {
        return extractPositiveIntegerParameter(request, CALENDARS_CONCURRENCY_PARAMETER, DEFAULT_CALENDARS_CONCURRENCY);
    }

    private static Integer extractPositiveIntegerParameter(Request request, String parameterName, int defaultValue) {
        try {
            return Optional.ofNullable(request.queryParams(parameterName))
                .map(Integer::parseInt)
                .map(value -> {
                    Preconditions.checkArgument(value > 0,
                        "Query parameter '%s' must be strictly positive, got: %d", parameterName, value);
                    return value;
                })
                .orElse(defaultValue);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format(
                "Illegal value supplied for query parameter '%s', expecting an integer",
                parameterName), e);
        }
    }

}
