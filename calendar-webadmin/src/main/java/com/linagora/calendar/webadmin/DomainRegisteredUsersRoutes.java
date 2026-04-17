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
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonExtractException;
import org.apache.james.webadmin.utils.JsonExtractor;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;

import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.exception.DomainNotFoundException;
import com.linagora.calendar.storage.exception.UserConflictException;
import com.linagora.calendar.storage.exception.UserNotFoundException;

import spark.Request;
import spark.Response;
import spark.Service;

public class DomainRegisteredUsersRoutes implements Routes {

    public static final String DOMAINS = "domains";
    private static final String DOMAIN_PARAM = ":domain";
    public static final String USERS_PATH = DOMAINS + "/" + DOMAIN_PARAM + "/registeredUsers";

    private final OpenPaaSUserDAO userDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final JsonTransformer jsonTransformer;
    private final JsonExtractor<CalendarUserRoutes.CalendarUserDTO> jsonExtractor;

    @Inject
    public DomainRegisteredUsersRoutes(OpenPaaSUserDAO userDAO, OpenPaaSDomainDAO domainDAO, JsonTransformer jsonTransformer) {
        this.userDAO = userDAO;
        this.domainDAO = domainDAO;
        this.jsonTransformer = jsonTransformer;
        this.jsonExtractor = new JsonExtractor<>(CalendarUserRoutes.CalendarUserDTO.class);
    }

    @Override
    public String getBasePath() {
        return DOMAINS;
    }

    @Override
    public void define(Service service) {
        service.get(USERS_PATH, this::getUsers, jsonTransformer);
        service.post(USERS_PATH, this::addUser);
        service.head(USERS_PATH, this::headUser);
        service.patch(USERS_PATH, this::updateUser, jsonTransformer);
    }

    private Object getUsers(Request request, Response response) {
        Domain domain = asDomain(request);

        if (request.queryParams("email") != null) {
            String email = request.queryParams("email");
            Username username = Username.of(email);

            OpenPaaSUser user = userDAO.retrieve(username).blockOptional()
                .orElseThrow(() -> ErrorResponder.builder()
                    .statusCode(HttpStatus.NOT_FOUND_404)
                    .type(ErrorResponder.ErrorType.NOT_FOUND)
                    .message("User does not exist")
                    .haltError());

            if (!username.getDomainPart().map(domain::equals).orElse(false)) {
                throw ErrorResponder.builder()
                    .statusCode(HttpStatus.NOT_FOUND_404)
                    .type(ErrorResponder.ErrorType.NOT_FOUND)
                    .message("User does not exist")
                    .haltError();
            }
            return CalendarUserRoutes.CalendarUserDTO.fromDomainObject(user);
        }

        return userDAO.listByDomain(domain)
            .map(CalendarUserRoutes.CalendarUserDTO::fromDomainObject)
            .collectList()
            .block();
    }

    private String addUser(Request request, Response response) throws JsonExtractException {
        Domain domain = asDomain(request);
        CalendarUserRoutes.CalendarUserDTO dto = jsonExtractor.parse(request.body());
        validateAddUserRequest(dto);

        Username username = Username.of(dto.email());
        if (!username.getDomainPart().map(domain::equals).orElse(false)) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Email domain must match URL domain: %s".formatted(domain.asString()))
                .haltError();
        }

        try {
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

    private String headUser(Request request, Response response) {
        Domain domain = asDomain(request);
        CalendarUserRoutes.HeadUserRequest headUserRequest = CalendarUserRoutes.HeadUserRequest.fromRequest(request);

        boolean exists = userExistsInDomain(headUserRequest, domain);
        response.status(exists ? HttpStatus.OK_200 : HttpStatus.NOT_FOUND_404);
        return Constants.EMPTY_BODY;
    }

    private boolean userExistsInDomain(CalendarUserRoutes.HeadUserRequest headUserRequest, Domain domain) {
        if (CalendarUserRoutes.Identifier.EMAIL.equals(headUserRequest.identifier())) {
            Username username = Username.of(headUserRequest.value());
            if (!username.getDomainPart().map(domain::equals).orElse(false)) {
                return false;
            }
            return userDAO.retrieve(username).blockOptional().isPresent();
        } else {
            return userDAO.retrieve(new OpenPaaSId(headUserRequest.value()))
                .blockOptional()
                .filter(user -> user.username().getDomainPart().map(domain::equals).orElse(false))
                .isPresent();
        }
    }

    private String updateUser(Request request, Response response) throws JsonExtractException {
        Domain domain = asDomain(request);
        String id = request.queryParams("id");
        CalendarUserRoutes.CalendarUserDTO dto = jsonExtractor.parse(request.body());

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

        OpenPaaSId openPaaSId = new OpenPaaSId(id);
        OpenPaaSUser existing = userDAO.retrieve(openPaaSId).blockOptional()
            .orElseThrow(() -> ErrorResponder.builder()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .type(ErrorResponder.ErrorType.NOT_FOUND)
                .message("User with id " + id + " not found")
                .haltError());

        if (!existing.username().getDomainPart().map(domain::equals).orElse(false)) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .type(ErrorResponder.ErrorType.NOT_FOUND)
                .message("User with id " + id + " not found")
                .haltError();
        }

        try {
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
        }
    }

    private Domain asDomain(Request request) {
        String domainName = request.params("domain");
        try {
            Domain domain = Domain.of(domainName);
            domainDAO.retrieve(domain)
                .blockOptional()
                .orElseThrow(() -> ErrorResponder.builder()
                    .statusCode(HttpStatus.NOT_FOUND_404)
                    .type(ErrorResponder.ErrorType.NOT_FOUND)
                    .message("Domain not found: %s".formatted(domainName))
                    .haltError());
            return domain;
        } catch (IllegalArgumentException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid domain: %s".formatted(domainName))
                .cause(e)
                .haltError();
        }
    }

    private void validateAddUserRequest(CalendarUserRoutes.CalendarUserDTO dto) {
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
}
