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
import org.apache.james.task.TaskId;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.linagora.calendar.dav.DavClientException;

import spark.HaltException;
import spark.Response;

final class WebAdminRouteUtils {
    private static final String TASK_ID_FIELD = "taskId";

    static <T> T wrapDavErrors(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (DavClientException e) {
            throw serverError(e);
        }
    }

    static HaltException serverError(DavClientException e) {
        return ErrorResponder.builder()
            .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
            .type(ErrorResponder.ErrorType.SERVER_ERROR)
            .message("Error while calling the DAV server")
            .cause(e)
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
