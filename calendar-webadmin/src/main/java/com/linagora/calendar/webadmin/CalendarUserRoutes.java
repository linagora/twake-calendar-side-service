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

import java.util.List;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonExtractException;
import org.apache.james.webadmin.utils.JsonExtractor;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;

import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import spark.Request;
import spark.Response;
import spark.Service;
import spark.Spark;

public class CalendarUserRoutes implements Routes {
    public record CalendarUserDTO(String email, String firstname, String lastname, String id) {}

    public static final String BASE_PATH = "/registeredUsers";

    private final OpenPaaSUserDAO userDAO;
    private final JsonTransformer jsonTransformer;
    private final JsonExtractor<CalendarUserDTO> jsonExtractor;

    @Inject
    public CalendarUserRoutes(OpenPaaSUserDAO userDAO, JsonTransformer jsonTransformer) {
        this.userDAO = userDAO;
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

        service.post(BASE_PATH, this::addUser, jsonTransformer);

        service.head(BASE_PATH, this::headUser);
    }

    private List<CalendarUserDTO> getUsers(Request request, Response response) {
        try {
            return userDAO.list()
                .map(user -> new CalendarUserDTO(
                    user.username().asString(),
                    user.firstname(),
                    user.lastname(),
                    user.id().value()
                ))
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
        } catch (IllegalStateException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
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
        String email = request.queryParams("email");
        String id = request.queryParams("id");

        validateHeadUserRequest(email, id);

        boolean exists;
        try {
            if (StringUtils.isNotEmpty(email)) {
                exists = userDAO.retrieve(Username.of(email)).blockOptional().isPresent();
            } else {
                exists = userDAO.retrieve(new OpenPaaSId(id)).blockOptional().isPresent();
            }

            if (exists) {
                response.status(HttpStatus.NO_CONTENT_204);
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

    private void validateHeadUserRequest(String email, String id) {
        if (StringUtils.isEmpty(email) && StringUtils.isEmpty(id)) {
            throw Spark.halt(HttpStatus.BAD_REQUEST_400);
        }

        if (!StringUtils.isEmpty(email) && !StringUtils.isEmpty(id)) {
            throw Spark.halt(HttpStatus.BAD_REQUEST_400);
        }
    }
}
