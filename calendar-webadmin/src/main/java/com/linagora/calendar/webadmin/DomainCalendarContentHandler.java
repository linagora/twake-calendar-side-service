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

import static com.linagora.calendar.dav.ResourceService.ONLY_ACTIVE;
import static org.apache.james.webadmin.Constants.SEPARATOR;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.james.task.TaskId;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.dav.importer.EventToImport;
import com.linagora.calendar.webadmin.service.CalendarImportService;
import com.linagora.calendar.webadmin.service.DomainCalendar;
import com.linagora.calendar.webadmin.task.DomainCalendarImportTask;

import reactor.core.publisher.Mono;
import spark.Request;
import spark.Response;

/**
 * Counting, exporting and importing the events of a calendar a domain owns: a team calendar or a resource.
 *
 * <p>Both are plain DAV calendars, reached with the technical token of their domain. Only the way their
 * identifier is resolved differs, hence these handlers shared by {@link TeamCalendarRoutes} and
 * {@link ResourceRoutes}.
 */
public class DomainCalendarContentHandler {

    /**
     * Resolves the calendar a request targets. {@code onlyActive} rejects the calendars that may be read but
     * no longer written to - resources flagged as deleted - and is thus only set for imports.
     */
    @FunctionalInterface
    public interface CalendarResolver {
        DomainCalendar resolve(boolean onlyActive);
    }

    private static final String ACTION_QUERY_PARAM = "action";
    private static final String EXPORT_ACTION = "export";
    private static final String IMPORT_ACTION = "import";
    private static final String ICS_CONTENT_TYPE = "text/calendar; charset=utf-8";
    private static final String ICS_CONTENT_DISPOSITION = "attachment; filename=calendar.ics";

    private static final String FIELD_TASK_ID = "taskId";
    private static final String FIELD_COUNT = "count";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CalDavClient calDavClient;
    private final CalendarImportService calendarImportService;
    private final TaskManager taskManager;

    @Inject
    public DomainCalendarContentHandler(CalDavClient calDavClient, CalendarImportService calendarImportService,
                                        TaskManager taskManager) {
        this.calDavClient = calDavClient;
        this.calendarImportService = calendarImportService;
        this.taskManager = taskManager;
    }

    public String countEvents(Response response, DomainCalendar calendar) {
        long count = wrapDavErrors(() -> calDavClient.findCalendarEventIds(calendar.domainId(), calendar.calendarURL())
            .count()
            .block());

        response.status(HttpStatus.OK_200);
        response.type(Constants.JSON_CONTENT_TYPE);
        return OBJECT_MAPPER.createObjectNode()
            .put(FIELD_COUNT, count)
            .toString();
    }

    public String exportOrImport(Request request, Response response, CalendarResolver calendarResolver) {
        String action = StringUtils.trimToEmpty(request.queryParams(ACTION_QUERY_PARAM)).toLowerCase(Locale.US);

        return switch (action) {
            case EXPORT_ACTION -> exportCalendar(response, calendarResolver.resolve(!ONLY_ACTIVE));
            case IMPORT_ACTION -> importCalendar(request, response, calendarResolver.resolve(ONLY_ACTIVE));
            default -> throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid '%s' query parameter: '%s'. Supported values are: '%s', '%s'"
                    .formatted(ACTION_QUERY_PARAM, action, EXPORT_ACTION, IMPORT_ACTION))
                .haltError();
        };
    }

    private String exportCalendar(Response response, DomainCalendar calendar) {
        byte[] ics = wrapDavErrors(() -> calDavClient.export(calendar.domainId(), calendar.calendarURL())
            .switchIfEmpty(Mono.error(() -> new DavClientException("The DAV server does not support exporting calendar "
                + calendar.calendarURL().serialize())))
            .block());

        response.status(HttpStatus.OK_200);
        response.type(ICS_CONTENT_TYPE);
        response.header(HttpHeader.CONTENT_DISPOSITION.asString(), ICS_CONTENT_DISPOSITION);
        return new String(ics, StandardCharsets.UTF_8);
    }

    private String importCalendar(Request request, Response response, DomainCalendar calendar) {
        List<EventToImport> events = parseEvents(request);

        TaskId taskId = taskManager.submit(new DomainCalendarImportTask(calendarImportService, calendar, events));

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
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid request body: %s".formatted(Optional.ofNullable(ExceptionUtils.getRootCause(e))
                    .map(Throwable::getMessage)
                    .orElse(e.getMessage())))
                .cause(e)
                .haltError();
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
