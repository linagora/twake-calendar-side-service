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

import static org.apache.james.webadmin.Constants.SEPARATOR;

import java.util.Optional;
import java.util.function.Supplier;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.james.core.Username;
import org.apache.james.task.TaskId;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.dav.DavResponseException;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import spark.HaltException;
import spark.Request;
import spark.Response;

final class WebAdminRouteUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebAdminRouteUtils.class);
    private static final String DAV_SERVER_ERROR_MESSAGE = "Error while calling the DAV server";
    private static final String DAV_REFUSAL_MESSAGE = "The DAV server refused the request";
    private static final String USERNAME_PARAM = ":username";
    private static final String TASK_ID_FIELD = "taskId";

    static OpenPaaSUser retrieveUser(Request request, OpenPaaSUserDAO userDAO) {
        String rawUsername = request.params(USERNAME_PARAM);
        try {
            Username username = Username.of(rawUsername);
            return userDAO.retrieve(username)
                .blockOptional()
                .orElseThrow(() -> ErrorResponder.builder()
                    .statusCode(HttpStatus.NOT_FOUND_404)
                    .type(ErrorResponder.ErrorType.NOT_FOUND)
                    .message("User does not exist")
                    .haltError());
        } catch (IllegalArgumentException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid username: %s", rawUsername)
                .cause(e)
                .haltError();
        }
    }

    static <T> T wrapDavErrors(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (DavClientException e) {
            throw davError(e);
        }
    }

    static HaltException davError(DavClientException e) {
        if (e instanceof DavResponseException davResponseException) {
            return davResponseError(davResponseException);
        }
        return ErrorResponder.builder()
            .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
            .type(ErrorResponder.ErrorType.SERVER_ERROR)
            .message(DAV_SERVER_ERROR_MESSAGE)
            .cause(e)
            .haltError();
    }

    private static HaltException davResponseError(DavResponseException e) {
        return davRefusalType(e.statusCode())
            .map(type -> davRefusal(e, type))
            .orElseGet(() -> unexpectedDavResponse(e));
    }

    private static Optional<ErrorResponder.ErrorType> davRefusalType(int davStatusCode) {
        return switch (davStatusCode) {
            case HttpStatus.BAD_REQUEST_400 -> Optional.of(ErrorResponder.ErrorType.INVALID_ARGUMENT);
            case HttpStatus.NOT_FOUND_404 -> Optional.of(ErrorResponder.ErrorType.NOT_FOUND);
            case HttpStatus.FORBIDDEN_403, HttpStatus.CONFLICT_409 -> Optional.of(ErrorResponder.ErrorType.WRONG_STATE);
            default -> Optional.empty();
        };
    }

    private static HaltException davRefusal(DavResponseException e, ErrorResponder.ErrorType type) {
        LOGGER.info("DAV server refused the request: {}", e.getMessage());
        return ErrorResponder.builder()
            .statusCode(e.statusCode())
            .type(type)
            .message(e.davMessage().orElse(DAV_REFUSAL_MESSAGE))
            .haltError();
    }

    private static HaltException unexpectedDavResponse(DavResponseException e) {
        LOGGER.error(DAV_SERVER_ERROR_MESSAGE, e);
        return ErrorResponder.builder()
            .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
            .type(ErrorResponder.ErrorType.SERVER_ERROR)
            .message(DAV_SERVER_ERROR_MESSAGE)
            .cause(new DavClientException(e.summary()))
            .haltError();
    }

    static HaltException invalidBody(Exception e) {
        String detail = Optional.ofNullable(ExceptionUtils.getRootCause(e))
            .map(Throwable::getMessage)
            .orElse(e.getMessage());
        return ErrorResponder.builder()
            .statusCode(HttpStatus.BAD_REQUEST_400)
            .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
            .message("Invalid request body: %s".formatted(detail))
            .cause(e)
            .haltError();
    }

    static <T> T parseDtoBody(Request request, Class<T> type, ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(request.bodyAsBytes(), type);
        } catch (Exception e) {
            throw invalidBody(e);
        }
    }

    static String createdTaskResponse(Response response, TaskId taskId) {
        response.status(HttpStatus.CREATED_201);
        response.header(HttpHeader.LOCATION.asString(), TasksRoutes.BASE + SEPARATOR + taskId.asString());
        response.type(Constants.JSON_CONTENT_TYPE);
        return JsonNodeFactory.instance.objectNode()
            .put(TASK_ID_FIELD, taskId.asString())
            .toString();
    }

    private WebAdminRouteUtils() {
    }
}
