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

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.james.core.Username;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import spark.HaltException;
import spark.Request;
import spark.Response;
import spark.Service;

/**
 * Administrative management of user calendars. Calls are proxied to the Sabre
 * DAV server, impersonating the targeted user.
 */
public class UserCalendarRoutes implements Routes {

    public static final String BASE_PATH = "/users";

    private static final String USERNAME_PARAM = ":username";
    private static final String CALENDAR_ID_PARAM = ":calendarId";
    private static final String CALENDARS_PATH = BASE_PATH + SEPARATOR + USERNAME_PARAM + SEPARATOR + "calendars";
    private static final String CALENDAR_PATH = CALENDARS_PATH + SEPARATOR + CALENDAR_ID_PARAM;
    private static final String PUBLIC_RIGHT_PATH = CALENDAR_PATH + SEPARATOR + "publicRight";
    private static final String INVITEE_PATH = CALENDAR_PATH + SEPARATOR + "invitee";

    private static final String FIELD_ID = "id";
    private static final String FIELD_NAME = "dav:name";
    private static final String FIELD_COLOR = "apple:color";
    private static final String FIELD_DESCRIPTION = "caldav:description";
    private static final String FIELD_PUBLIC_RIGHT = "public_right";

    private static final Set<String> CREATE_FIELDS = ImmutableSet.of(FIELD_ID, FIELD_NAME, FIELD_COLOR, FIELD_DESCRIPTION);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    private final OpenPaaSUserDAO userDAO;
    private final CalDavClient calDavClient;

    @Inject
    public UserCalendarRoutes(OpenPaaSUserDAO userDAO, CalDavClient calDavClient) {
        this.userDAO = userDAO;
        this.calDavClient = calDavClient;
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.get(CALENDARS_PATH, this::listCalendars);
        service.post(CALENDARS_PATH, this::createCalendar);
        service.delete(CALENDAR_PATH, this::deleteCalendar);
        service.patch(CALENDAR_PATH, this::updateCalendarProperties);
        service.post(PUBLIC_RIGHT_PATH, this::updatePublicRight);
        service.post(INVITEE_PATH, this::updateInvitees);
    }

    private String listCalendars(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);

        byte[] sabreResponse = wrapDavErrors(() -> calDavClient
            .findUserCalendarsAsBytes(user.username(), user.id(), CalDavClient.DEFAULT_FIND_USER_CALENDARS_PARAMS)
            .block());

        response.status(HttpStatus.OK_200);
        response.type(Constants.JSON_CONTENT_TYPE);
        return new String(sabreResponse, StandardCharsets.UTF_8);
    }

    private String createCalendar(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        JsonNode body = parseBody(request);
        validateFields(body, CREATE_FIELDS);

        String name = body.path(FIELD_NAME).asText(null);
        if (StringUtils.isBlank(name)) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Field '%s' is required".formatted(FIELD_NAME))
                .haltError();
        }

        String calendarId = Optional.ofNullable(StringUtils.trimToNull(body.path(FIELD_ID).asText(null)))
            .orElseGet(() -> UUID.randomUUID().toString());

        CalDavClient.NewCalendar newCalendar = new CalDavClient.NewCalendar(calendarId,
            name,
            body.path(FIELD_COLOR).asText(""),
            body.path(FIELD_DESCRIPTION).asText(""));

        wrapDavErrors(() -> calDavClient.createNewCalendar(user.username(), user.id(), newCalendar).block());

        response.status(HttpStatus.CREATED_201);
        response.type(Constants.JSON_CONTENT_TYPE);
        return "{\"id\":\"" + calendarId + "\"}";
    }

    private String deleteCalendar(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        CalendarURL calendarURL = retrieveExistingCalendar(request, user);

        wrapDavErrors(() -> calDavClient.deleteCalendar(user.username(), calendarURL).block());

        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
    }

    private String updateCalendarProperties(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        CalDavClient.CalendarPropertiesUpdate update = parseBody(request, CalDavClient.CalendarPropertiesUpdate.class);
        CalendarURL calendarURL = retrieveExistingCalendar(request, user);

        wrapDavErrors(() -> calDavClient.updateCalendarProperties(user.username(), calendarURL, update).block());

        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
    }

    private String updatePublicRight(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        CalDavClient.PublicRight publicRight = parsePublicRight(request);
        CalendarURL calendarURL = retrieveExistingCalendar(request, user);

        wrapDavErrors(() -> calDavClient.updateCalendarAcl(user.username(), calendarURL, publicRight).block());

        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
    }

    private String updateInvitees(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        CalDavClient.CalendarSharingUpdate sharingUpdate = parseBody(request, CalDavClient.CalendarSharingUpdate.class);
        CalendarURL calendarURL = retrieveExistingCalendar(request, user);

        wrapDavErrors(() -> calDavClient.updateCalendarShares(user.username(), calendarURL, sharingUpdate).block());

        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
    }

    private OpenPaaSUser retrieveUser(Request request) {
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

    private CalendarURL retrieveExistingCalendar(Request request, OpenPaaSUser user) {
        String calendarId = request.params(CALENDAR_ID_PARAM);
        CalendarURL calendarURL = new CalendarURL(user.id(), new OpenPaaSId(calendarId));

        boolean exists = wrapDavErrors(() -> calDavClient.calendarExists(user.username(), calendarURL).block());
        if (!exists) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .type(ErrorResponder.ErrorType.NOT_FOUND)
                .message("Calendar does not exist")
                .haltError();
        }
        return calendarURL;
    }

    private CalDavClient.PublicRight parsePublicRight(Request request) {
        JsonNode body = parseBody(request);
        JsonNode publicRightNode = body.path(FIELD_PUBLIC_RIGHT);
        if (!publicRightNode.isTextual()) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Field '%s' is required".formatted(FIELD_PUBLIC_RIGHT))
                .haltError();
        }
        String publicRight = publicRightNode.asText();
        return switch (publicRight) {
            case "" -> CalDavClient.PublicRight.HIDE_ALL_EVENT;
            case "{DAV:}read" -> CalDavClient.PublicRight.READ;
            default -> throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid '%s' value: '%s'. Supported values are: '' and '{DAV:}read'".formatted(FIELD_PUBLIC_RIGHT, publicRight))
                .haltError();
        };
    }

    private JsonNode parseBody(Request request) {
        try {
            JsonNode body = OBJECT_MAPPER.readTree(request.bodyAsBytes());
            if (body == null || !body.isObject()) {
                throw new IllegalArgumentException("Request body must be a JSON object");
            }
            return body;
        } catch (Exception e) {
            throw invalidBody(e);
        }
    }

    private <T> T parseBody(Request request, Class<T> type) {
        try {
            return OBJECT_MAPPER.readValue(request.bodyAsBytes(), type);
        } catch (Exception e) {
            throw invalidBody(e);
        }
    }

    private HaltException invalidBody(Exception e) {
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

    private void validateFields(JsonNode body, Set<String> allowedFields) {
        Iterator<String> fieldNames = body.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!allowedFields.contains(fieldName)) {
                throw ErrorResponder.builder()
                    .statusCode(HttpStatus.BAD_REQUEST_400)
                    .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                    .message("Unknown field '%s'. Supported fields are: %s".formatted(fieldName, allowedFields))
                    .haltError();
            }
        }
    }

    private <T> T wrapDavErrors(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (DavClientException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .type(ErrorResponder.ErrorType.SERVER_ERROR)
                .message("Error while calling the DAV server")
                .cause(e)
                .haltError();
        }
    }
}
