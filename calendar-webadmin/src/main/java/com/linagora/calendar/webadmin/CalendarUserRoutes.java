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

import static com.linagora.calendar.webadmin.task.LdapUsersImportRunningOptions.DEFAULT_USERS_PER_SECOND;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.tasks.TaskFromRequestRegistry;
import org.apache.james.webadmin.tasks.TaskRegistrationKey;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonExtractException;
import org.apache.james.webadmin.utils.JsonExtractor;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;

import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.exception.DomainNotFoundException;
import com.linagora.calendar.storage.exception.UserConflictException;
import com.linagora.calendar.storage.exception.UserNotFoundException;
import com.linagora.calendar.webadmin.service.LdapUsersImportService;
import com.linagora.calendar.webadmin.task.LdapUsersImportRunningOptions;
import com.linagora.calendar.webadmin.task.LdapUsersImportTask;

import spark.Request;
import spark.Response;
import spark.Route;
import spark.Service;
import spark.Spark;

public class CalendarUserRoutes implements Routes {
    public static class LdapUsersImportRequestToTask extends TaskFromRequestRegistry.TaskRegistration {
        public static final TaskRegistrationKey TASK_NAME = TaskRegistrationKey.of("importFromLDAP");

        private static final String USERS_PER_SECOND = "usersPerSecond";

        @Inject
        public LdapUsersImportRequestToTask(LdapUsersImportService importService) {
            super(TASK_NAME, request -> {
                int eventsPerSecond = extractUsersPerSecond(request);
                return new LdapUsersImportTask(importService, LdapUsersImportRunningOptions.of(eventsPerSecond));
            });
        }

        private static Integer extractUsersPerSecond(Request request) {
            try {
                return Optional.ofNullable(request.queryParams(USERS_PER_SECOND))
                    .map(Integer::parseInt)
                    .orElse(DEFAULT_USERS_PER_SECOND);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(String.format("Illegal value supplied for query parameter '%s', expecting a " +
                    "strictly positive optional integer", USERS_PER_SECOND), e);
            }
        }
    }

    public record CalendarUserDTO(String email, String firstname, String lastname, String id) {
        public static CalendarUserDTO fromDomainObject(OpenPaaSUser user) {
            return new CalendarUserDTO(
                user.username().asString(),
                user.firstname(),
                user.lastname(),
                user.id().value());
        }
    }

    public enum Identifier {
        EMAIL,
        ID
    }

    public record HeadUserRequest(Identifier identifier, String value) {
        public static HeadUserRequest fromRequest(Request request) {
            String email = request.queryParams("email");
            String id = request.queryParams("id");

            // only one of the two should be set
            if (StringUtils.isEmpty(email) == StringUtils.isEmpty(id)) {
                throw Spark.halt(HttpStatus.BAD_REQUEST_400);
            } else if (StringUtils.isNotEmpty(email)) {
                return new HeadUserRequest(Identifier.EMAIL, email);
            } else {
                return new HeadUserRequest(Identifier.ID, id);
            }
        }
    }

    public static final String BASE_PATH = "/registeredUsers";
    public static final String USER_TASKS = "userTasks";

    private final OpenPaaSUserDAO userDAO;
    private final JsonTransformer jsonTransformer;
    private final JsonExtractor<CalendarUserDTO> jsonExtractor;
    private final TaskManager taskManager;
    private final Set<TaskFromRequestRegistry.TaskRegistration> taskRegistrations;

    @Inject
    public CalendarUserRoutes(OpenPaaSUserDAO userDAO, JsonTransformer jsonTransformer, TaskManager taskManager,
                              @Named(USER_TASKS) Set<TaskFromRequestRegistry.TaskRegistration> taskRegistrations) {
        this.userDAO = userDAO;
        this.jsonTransformer = jsonTransformer;
        this.taskManager = taskManager;
        this.jsonExtractor = new JsonExtractor<>(CalendarUserDTO.class);
        this.taskRegistrations = taskRegistrations;
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.get(BASE_PATH, this::getUsers, jsonTransformer);

        service.post(BASE_PATH, this::addUser, jsonTransformer);

        service.head(BASE_PATH, this::headUser);

        service.patch(BASE_PATH, this::updateUser, jsonTransformer);

        defineTaskRoute().ifPresent(route -> service.post(BASE_PATH, route, jsonTransformer));
    }

    public Optional<Route> defineTaskRoute() {
        return TaskFromRequestRegistry.builder()
            .parameterName("task")
            .registrations(taskRegistrations)
            .buildAsRouteOptional(taskManager);
    }

    private List<CalendarUserDTO> getUsers(Request request, Response response) {
        try {
            return userDAO.list()
                .map(CalendarUserDTO::fromDomainObject)
                .collectList()
                .block();
        } catch (Exception e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .type(ErrorResponder.ErrorType.SERVER_ERROR)
                .message("Error while fetching all users")
                .cause(e)
                .haltError();
        }
    }

    private String addUser(Request request, Response response) throws JsonExtractException {
        CalendarUserDTO dto = jsonExtractor.parse(request.body());
        validateAddUserRequest(dto);

        try {
            Username username = Username.of(dto.email());
            userDAO.add(username, dto.firstname(), dto.lastname()).block();
            response.status(HttpStatus.CREATED_201);
            return Constants.EMPTY_BODY;
        } catch (DomainNotFoundException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message(e.getMessage())
                .haltError();
        } catch (UserConflictException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.CONFLICT_409)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message(e.getMessage())
                .haltError();
        } catch (Exception e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .type(ErrorResponder.ErrorType.SERVER_ERROR)
                .message(String.format("Error while adding user '%s'", dto.email))
                .cause(e)
                .haltError();
        }
    }

    private void validateAddUserRequest(CalendarUserDTO dto) {
        if (StringUtils.isEmpty(dto.email())) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Missing email")
                .haltError();
        }

        if (StringUtils.isEmpty(dto.firstname())) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Missing firstname")
                .haltError();
        }

        if (StringUtils.isEmpty(dto.lastname())) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Missing lastname")
                .haltError();
        }
    }

    private String headUser(Request request, Response response) {
        HeadUserRequest headUserRequest = HeadUserRequest.fromRequest(request);

        try {
            if (userExists(headUserRequest)) {
                response.status(HttpStatus.OK_200);
            } else {
                response.status(HttpStatus.NOT_FOUND_404);
            }
            return Constants.EMPTY_BODY;
        } catch (Exception e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .type(ErrorResponder.ErrorType.SERVER_ERROR)
                .message("Error while checking user existence")
                .cause(e)
                .haltError();
        }
    }

    private boolean userExists(HeadUserRequest headUserRequest) {
        if (Identifier.EMAIL.equals(headUserRequest.identifier())) {
            return userDAO.retrieve(Username.of(headUserRequest.value())).blockOptional().isPresent();
        } else {
            return userDAO.retrieve(new OpenPaaSId(headUserRequest.value())).blockOptional().isPresent();
        }
    }

    private String updateUser(Request request, Response response) throws JsonExtractException {
        String id = request.queryParams("id");
        CalendarUserDTO dto = jsonExtractor.parse(request.body());

        validateUpdateUserRequest(id, dto);

        try {
            OpenPaaSId openPaaSId = new OpenPaaSId(id);
            userDAO.update(openPaaSId, Username.of(dto.email()), dto.firstname(), dto.lastname()).block();
            response.status(HttpStatus.NO_CONTENT_204);
            return Constants.EMPTY_BODY;
        } catch (DomainNotFoundException | UserNotFoundException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message(e.getMessage())
                .haltError();
        } catch (UserConflictException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.CONFLICT_409)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message(e.getMessage())
                .haltError();
        } catch (Exception e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .type(ErrorResponder.ErrorType.SERVER_ERROR)
                .message("Error while updating user with id '%s'".formatted(id))
                .cause(e)
                .haltError();
        }
    }

    private void validateUpdateUserRequest(String id, CalendarUserDTO dto) {
        if (StringUtils.isEmpty(id)) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Missing 'id' query parameter")
                .haltError();
        }

        if (StringUtils.isEmpty(dto.email()) || StringUtils.isEmpty(dto.firstname()) || StringUtils.isEmpty(dto.lastname())) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Missing one or more required fields: email, firstname, lastname")
                .haltError();
        }
    }
}
