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
import static org.apache.james.webadmin.Constants.SEPARATOR;

import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.task.Task;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.tasks.TaskFromRequestRegistry;
import org.apache.james.webadmin.tasks.TaskRegistrationKey;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.webadmin.service.CommonContactRepublishService;
import com.linagora.calendar.webadmin.task.CommonContactRepublishTask;
import com.linagora.calendar.webadmin.task.CommonContactRepublishTask.Scope;
import com.linagora.calendar.webadmin.task.CommonContactRepublishTask.Scope.Selection;

import spark.Request;
import spark.Route;
import spark.Service;

public class ContactRoutes implements Routes {
    private static final String ACTION_PARAMETER = "action";
    private static final String CONTACTS_PER_SECOND_PARAMETER = "contactsPerSecond";
    private static final String SCOPE_PARAMETER = "scope";
    private static final String USERNAME_PARAMETER = "username";
    private static final String DOMAIN_PARAMETER = "domain";

    public static class CommonContactRepublishRequestToTask extends TaskFromRequestRegistry.TaskRegistration {
        public static final TaskRegistrationKey TASK_NAME = TaskRegistrationKey.of("republish");

        @Inject
        public CommonContactRepublishRequestToTask(CommonContactRepublishService republishService,
                                                   OpenPaaSUserDAO userDAO,
                                                   OpenPaaSDomainDAO domainDAO) {
            super(TASK_NAME, request -> taskFromRequest(request, republishService, userDAO, domainDAO));
        }

        private static Task taskFromRequest(Request request, CommonContactRepublishService republishService,
                                            OpenPaaSUserDAO userDAO, OpenPaaSDomainDAO domainDAO) {
            return new CommonContactRepublishTask(republishService,
                CommonContactRepublishTask.RunningOptions.of(extractContactsPerSecond(request)),
                extractScope(request, userDAO, domainDAO));
        }

        private static Scope extractScope(Request request, OpenPaaSUserDAO userDAO, OpenPaaSDomainDAO domainDAO) {
            Optional<Selection> selection = extractSelection(request);
            return extractPathParameter(request, USERNAME_PARAMETER)
                .map(username -> userScope(selection, userDAO, username))
                .or(() -> extractPathParameter(request, DOMAIN_PARAMETER)
                    .map(domain -> domainScope(selection, domainDAO, domain)))
                .orElseGet(() -> new Scope.All(selection.orElse(Selection.BOTH)));
        }

        private static Optional<Selection> extractSelection(Request request) {
            return extractQueryParameter(request, SCOPE_PARAMETER)
                .map(value -> {
                    try {
                        return Selection.from(value);
                    } catch (IllegalArgumentException e) {
                        throw ErrorResponder.builder()
                            .statusCode(HttpStatus.BAD_REQUEST_400)
                            .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                            .message("Invalid '%s' parameter: %s. Supported values: %s"
                                .formatted(SCOPE_PARAMETER, value, Selection.supportedQueryParameters()))
                            .cause(e)
                            .haltError();
                    }
                });
        }

        private static Scope userScope(Optional<Selection> selection, OpenPaaSUserDAO userDAO, String username) {
            selection.ifPresent(ignored -> {
                throw ErrorResponder.builder()
                    .statusCode(HttpStatus.BAD_REQUEST_400)
                    .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                    .message("'%s' parameter is not supported by the user endpoint".formatted(SCOPE_PARAMETER))
                    .haltError();
            });

            return userDAO.retrieve(Username.of(username))
                .blockOptional()
                .<Scope>map(Scope.ForUser::new)
                .orElseThrow(() -> ErrorResponder.builder()
                    .statusCode(HttpStatus.NOT_FOUND_404)
                    .type(ErrorResponder.ErrorType.NOT_FOUND)
                    .message("user not found: " + username)
                    .haltError());
        }

        private static Scope domainScope(Optional<Selection> selection, OpenPaaSDomainDAO domainDAO, String domain) {
            return domainDAO.retrieve(Domain.of(domain))
                .blockOptional()
                .<Scope>map(openPaaSDomain -> new Scope.ForDomain(openPaaSDomain, selection.orElse(Selection.BOTH)))
                .orElseThrow(() -> ErrorResponder.builder()
                    .statusCode(HttpStatus.NOT_FOUND_404)
                    .type(ErrorResponder.ErrorType.NOT_FOUND)
                    .message("domain not found: " + domain)
                    .haltError());
        }
    }

    public static final String BASE_PATH = "/contacts";
    public static final String USER_PATH = "/users" + SEPARATOR + ":" + USERNAME_PARAMETER + SEPARATOR + "contacts";
    public static final String DOMAIN_PATH = "/domains" + SEPARATOR + ":" + DOMAIN_PARAMETER + SEPARATOR + "contacts";

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
        Route republishRoute = republishRoute();
        service.post(BASE_PATH, republishRoute, jsonTransformer);
        service.post(USER_PATH, republishRoute, jsonTransformer);
        service.post(DOMAIN_PATH, republishRoute, jsonTransformer);
    }

    private Route republishRoute() {
        return TaskFromRequestRegistry.builder()
            .parameterName(ACTION_PARAMETER)
            .registrations(republishRegistration)
            .buildAsRoute(taskManager);
    }

    private static Optional<String> extractPathParameter(Request request, String parameterName) {
        return Optional.ofNullable(StringUtils.trimToNull(request.params(parameterName)));
    }

    private static Optional<String> extractQueryParameter(Request request, String parameterName) {
        return Optional.ofNullable(StringUtils.trimToNull(request.queryParams(parameterName)));
    }

    private static int extractContactsPerSecond(Request request) {
        try {
            return extractQueryParameter(request, CONTACTS_PER_SECOND_PARAMETER)
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
