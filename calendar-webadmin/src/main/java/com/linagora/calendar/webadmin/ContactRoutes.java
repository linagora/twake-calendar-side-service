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

import static com.linagora.calendar.webadmin.task.CommonContactRepublishTask.RunningOptions.DEFAULT_CONTACTS_PER_SECOND;

import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.tasks.TaskFromRequestRegistry;
import org.apache.james.webadmin.tasks.TaskRegistrationKey;
import org.apache.james.webadmin.utils.JsonTransformer;

import com.google.common.base.Preconditions;
import com.linagora.calendar.webadmin.service.CommonContactRepublishService;
import com.linagora.calendar.webadmin.task.CommonContactRepublishTask;

import spark.Request;
import spark.Service;

public class ContactRoutes implements Routes {
    private static final String ACTION_PARAMETER = "action";
    private static final String CONTACTS_PER_SECOND_PARAMETER = "contactsPerSecond";

    public static class CommonContactRepublishRequestToTask extends TaskFromRequestRegistry.TaskRegistration {
        public static final TaskRegistrationKey TASK_NAME = TaskRegistrationKey.of("republish");

        @Inject
        public CommonContactRepublishRequestToTask(CommonContactRepublishService republishService) {
            super(TASK_NAME, request -> new CommonContactRepublishTask(republishService,
                CommonContactRepublishTask.RunningOptions.of(extractContactsPerSecond(request))));
        }
    }

    public static final String BASE_PATH = "/contacts";

    private final JsonTransformer jsonTransformer;
    private final TaskManager taskManager;
    private final CommonContactRepublishRequestToTask republishRegistration;

    @Inject
    public ContactRoutes(JsonTransformer jsonTransformer,
                         TaskManager taskManager,
                         CommonContactRepublishRequestToTask republishRegistration) {
        this.jsonTransformer = jsonTransformer;
        this.taskManager = taskManager;
        this.republishRegistration = republishRegistration;
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.post(BASE_PATH, TaskFromRequestRegistry.builder()
            .parameterName(ACTION_PARAMETER)
            .registrations(republishRegistration)
            .buildAsRoute(taskManager), jsonTransformer);
    }

    private static int extractContactsPerSecond(Request request) {
        try {
            return Optional.ofNullable(request.queryParams(CONTACTS_PER_SECOND_PARAMETER))
                .map(Integer::parseInt)
                .map(value -> {
                    Preconditions.checkArgument(value > 0,
                        "Query parameter '%s' must be strictly positive, got: %d", CONTACTS_PER_SECOND_PARAMETER, value);
                    return value;
                })
                .orElse(DEFAULT_CONTACTS_PER_SECOND);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format(
                "Illegal value supplied for query parameter '%s', expecting an integer",
                CONTACTS_PER_SECOND_PARAMETER), e);
        }
    }
}
