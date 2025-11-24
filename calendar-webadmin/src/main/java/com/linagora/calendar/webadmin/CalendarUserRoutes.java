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

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.task.TaskId;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.Routes;
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
import com.linagora.calendar.webadmin.task.AddMissingFieldsTask;

import spark.Request;
import spark.Response;
import spark.Service;
import spark.Spark;

public class CalendarUserRoutes implements Routes {

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
    private static final String ACTION_PARAMETER = "action";
    private static final String ADD_MISSING_FIELDS_ACTION = "addMissingFields";

    private final OpenPaaSUserDAO userDAO;
    private final TaskManager taskManager;
    private final JsonTransformer jsonTransformer;
    private final JsonExtractor<CalendarUserDTO> jsonExtractor;

    @Inject
    public CalendarUserRoutes(OpenPaaSUserDAO userDAO, TaskManager taskManager, JsonTransformer jsonTransformer) {
        this.userDAO = userDAO;
        this.taskManager = taskManager;
        this.jsonTransformer = jsonTransformer;
        this.jsonExtractor = new JsonExtractor<>(CalendarUserDTO.class);
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.get(BASE_PATH, this::getUsers, jsonTransformer);

        service.post(BASE_PATH, this::post);

        service.head(BASE_PATH, this::headUser);

        service.patch(BASE_PATH, this::updateUser, jsonTransformer);
    }

    private Object getUsers(Request request, Response response) {
        if (request.queryParams("email") != null) {
            String email = request.queryParams("email");

            OpenPaaSUser user = userDAO.retrieve(Username.of(email)).blockOptional()
                .orElseThrow(() -> ErrorResponder.builder()
                    .statusCode(HttpStatus.NOT_FOUND_404)
                    .type(ErrorResponder.ErrorType.NOT_FOUND)
                    .message("User does not exist")
                    .haltError());
            return CalendarUserDTO.fromDomainObject(user);
        }

        return userDAO.list()
            .map(CalendarUserDTO::fromDomainObject)
            .collectList()
            .block();
    }

    private Object post(Request request, Response response) throws JsonExtractException {
        String action = request.queryParams(ACTION_PARAMETER);

        if (StringUtils.isEmpty(action)) {
            return addUser(request, response);
        } else if (ADD_MISSING_FIELDS_ACTION.equals(action)) {
            return submitAddMissingFieldsTask(response);
        } else {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid action: '%s'. Supported actions are: %s".formatted(action, ADD_MISSING_FIELDS_ACTION))
                .haltError();
        }
    }

    private String submitAddMissingFieldsTask(Response response) {
        AddMissingFieldsTask task = new AddMissingFieldsTask(userDAO);
        TaskId taskId = taskManager.submit(task);
        response.status(HttpStatus.CREATED_201);

        try {
            return "{\"taskId\":\"" + taskId.asString() + "\"}";
        } catch (Exception e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .type(ErrorResponder.ErrorType.SERVER_ERROR)
                .message("Error serializing task ID")
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

        if (userExists(headUserRequest)) {
            response.status(HttpStatus.OK_200);
        } else {
            response.status(HttpStatus.NOT_FOUND_404);
        }
        return Constants.EMPTY_BODY;
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
