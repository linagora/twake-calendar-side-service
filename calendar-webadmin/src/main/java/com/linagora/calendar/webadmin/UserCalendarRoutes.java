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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.james.core.Username;
import org.apache.james.task.TaskId;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.base.Preconditions;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalendarNotFoundException;
import com.linagora.calendar.dav.CalendarSharingUpdate;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.dav.dto.CalendarMirrorSource;
import com.linagora.calendar.dav.importer.EventToImport;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.webadmin.service.CalendarImportService;
import com.linagora.calendar.webadmin.task.CalendarImportTask;

import reactor.core.publisher.Mono;
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
    private static final String EVENT_COUNT_PATH = CALENDAR_PATH + SEPARATOR + "eventCount";

    private static final String ACTION_QUERY_PARAM = "action";
    private static final String EXPORT_ACTION = "export";
    private static final String IMPORT_ACTION = "import";
    private static final String ICS_CONTENT_TYPE = "text/calendar; charset=utf-8";
    private static final String ICS_CONTENT_DISPOSITION = "attachment; filename=calendar.ics";

    private static final String FIELD_ID = "id";
    private static final String FIELD_TASK_ID = "taskId";
    private static final String FIELD_COUNT = "count";
    private static final String FIELD_NAME = "dav:name";
    private static final String FIELD_COLOR = "apple:color";
    private static final String FIELD_DESCRIPTION = "caldav:description";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    public record CalendarCreationRequest(@JsonProperty(FIELD_ID) Optional<String> id,
                                          @JsonProperty(value = FIELD_NAME, required = true) String name,
                                          @JsonProperty(FIELD_COLOR) Optional<String> color,
                                          @JsonProperty(FIELD_DESCRIPTION) Optional<String> description) {
        public CalendarCreationRequest {
            Preconditions.checkArgument(StringUtils.isNotBlank(name), "Field '%s' is required", FIELD_NAME);
        }

        CalDavClient.NewCalendar toNewCalendar(String calendarId) {
            return new CalDavClient.NewCalendar(calendarId, name, color.orElse(""), description.orElse(""));
        }
    }

    private final OpenPaaSUserDAO userDAO;
    private final CalDavClient calDavClient;
    private final CalendarImportService calendarImportService;
    private final TaskManager taskManager;

    @Inject
    public UserCalendarRoutes(OpenPaaSUserDAO userDAO, CalDavClient calDavClient,
                              CalendarImportService calendarImportService, TaskManager taskManager) {
        this.userDAO = userDAO;
        this.calDavClient = calDavClient;
        this.calendarImportService = calendarImportService;
        this.taskManager = taskManager;
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.get(CALENDARS_PATH, this::listCalendars);
        service.get(EVENT_COUNT_PATH, this::countCalendarEvents);
        service.post(CALENDARS_PATH, this::createCalendar);
        service.post(CALENDAR_PATH, this::exportOrImportCalendar);
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

    private String countCalendarEvents(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        CalendarURL calendarURL = retrieveExistingCalendar(request, user);

        long count = wrapDavErrors(() -> calDavClient.findUserCalendarEventIds(user.username(), calendarURL)
            .count()
            .block());

        response.status(HttpStatus.OK_200);
        response.type(Constants.JSON_CONTENT_TYPE);
        return OBJECT_MAPPER.createObjectNode()
            .put(FIELD_COUNT, count)
            .toString();
    }

    private String createCalendar(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        CalendarCreationRequest creationRequest = parseBody(request, CalendarCreationRequest.class);

        String calendarId = creationRequest.id()
            .map(StringUtils::trimToNull)
            .orElseGet(() -> UUID.randomUUID().toString());

        wrapDavErrors(() -> calDavClient.createNewCalendar(user.username(), user.id(), creationRequest.toNewCalendar(calendarId)).block());

        response.status(HttpStatus.CREATED_201);
        response.type(Constants.JSON_CONTENT_TYPE);
        return OBJECT_MAPPER.createObjectNode()
            .put(FIELD_ID, calendarId)
            .toString();
    }

    private String exportOrImportCalendar(Request request, Response response) {
        String action = StringUtils.trimToEmpty(request.queryParams(ACTION_QUERY_PARAM));

        return switch (action.toLowerCase(Locale.US)) {
            case EXPORT_ACTION -> exportCalendar(request, response);
            case IMPORT_ACTION -> importCalendar(request, response);
            default -> throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid '%s' query parameter: '%s'. Supported values are: '%s', '%s'"
                    .formatted(ACTION_QUERY_PARAM, action, EXPORT_ACTION, IMPORT_ACTION))
                .haltError();
        };
    }

    private String exportCalendar(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        CalendarURL calendarURL = retrieveExportableCalendar(request, user);

        byte[] ics = wrapDavErrors(() -> calDavClient.export(calendarURL, user.username())
            .switchIfEmpty(Mono.error(() -> new DavClientException("The DAV server does not support exporting calendar " + calendarURL.serialize())))
            .block());

        response.status(HttpStatus.OK_200);
        response.type(ICS_CONTENT_TYPE);
        response.header(HttpHeader.CONTENT_DISPOSITION.asString(), ICS_CONTENT_DISPOSITION);
        return new String(ics, StandardCharsets.UTF_8);
    }

    private String importCalendar(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        CalendarURL calendarURL = retrieveExistingCalendar(request, user);
        List<EventToImport> events = parseEvents(request);

        TaskId taskId = taskManager.submit(new CalendarImportTask(calendarImportService, user.username(), calendarURL, events));

        response.status(HttpStatus.CREATED_201);
        response.header(HttpHeader.LOCATION.asString(), TasksRoutes.BASE + SEPARATOR + taskId.asString());
        response.type(Constants.JSON_CONTENT_TYPE);
        return OBJECT_MAPPER.createObjectNode()
            .put(FIELD_TASK_ID, taskId.asString())
            .toString();
    }

    private List<EventToImport> parseEvents(Request request) {
        List<EventToImport> events;
        try {
            events = EventToImport.parse(request.bodyAsBytes());
        } catch (Exception e) {
            throw invalidBody(e);
        }
        if (events.isEmpty()) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid request body: no event to import")
                .haltError();
        }
        return events;
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
        CalDavClient.PublicRight publicRight = PublicRightParser.parse(request);
        CalendarURL calendarURL = retrieveExistingCalendar(request, user);

        wrapDavErrors(() -> calDavClient.updateCalendarAcl(user.username(), calendarURL, publicRight).block());

        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
    }

    private String updateInvitees(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        CalendarSharingUpdate sharingUpdate = parseBody(request, CalendarSharingUpdate.class);
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
        CalendarURL calendarURL = requestedCalendar(request, user);

        boolean exists = wrapDavErrors(() -> calDavClient.calendarExists(user.username(), calendarURL).block());
        if (!exists) {
            throw calendarNotFound();
        }
        return calendarURL;
    }

    /**
     * A subscription to a public calendar holds no event of its own: the source calendar needs to be exported
     * instead. Delegated calendars, on the other hand, are exported through the path of the delegate, the only
     * one they are granted to read.
     */
    private CalendarURL retrieveExportableCalendar(Request request, OpenPaaSUser user) {
        CalendarURL calendarURL = requestedCalendar(request, user);

        return wrapDavErrors(() -> calDavClient.fetchCalendarMetadata(user.username(), calendarURL)
            .map(metadata -> CalendarMirrorSource.parse(metadata).subscribedSource().orElse(calendarURL))
            .onErrorMap(CalendarNotFoundException.class, e -> calendarNotFound())
            .block());
    }

    private CalendarURL requestedCalendar(Request request, OpenPaaSUser user) {
        return new CalendarURL(user.id(), new OpenPaaSId(request.params(CALENDAR_ID_PARAM)));
    }

    private HaltException calendarNotFound() {
        return ErrorResponder.builder()
            .statusCode(HttpStatus.NOT_FOUND_404)
            .type(ErrorResponder.ErrorType.NOT_FOUND)
            .message("Calendar does not exist")
            .haltError();
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
