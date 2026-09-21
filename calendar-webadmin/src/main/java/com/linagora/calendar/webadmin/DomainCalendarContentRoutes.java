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
import org.apache.james.core.Domain;
import org.apache.james.task.TaskId;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.dav.ResourceService;
import com.linagora.calendar.dav.importer.EventToImport;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.TeamCalendarNotFoundException;
import com.linagora.calendar.storage.exception.DomainNotFoundException;
import com.linagora.calendar.storage.model.ResourceId;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.model.TeamCalendarId;
import com.linagora.calendar.webadmin.service.CalendarImportService;
import com.linagora.calendar.webadmin.task.DomainCalendarImportTask;
import com.linagora.calendar.webadmin.task.DomainCalendarImportTask.CalendarType;

import reactor.core.publisher.Mono;
import spark.HaltException;
import spark.Request;
import spark.Response;
import spark.Service;

/**
 * Counting, exporting and importing the events of the calendars a domain owns: team calendars and resources.
 *
 * <p>Both are plain DAV calendars, reached with the technical token of their domain. Only the way their
 * identifier is resolved differs, hence a single set of handlers serving the two URL families.
 */
public class DomainCalendarContentRoutes implements Routes {

    public static final String BASE_PATH = "domains";

    private static final String DOMAIN_PARAM = ":domain";
    private static final String CALENDAR_ID_PARAM = ":calendarId";
    private static final String DOMAIN_PATH = BASE_PATH + SEPARATOR + DOMAIN_PARAM;
    private static final String TEAM_CALENDAR_PATH = DOMAIN_PATH + SEPARATOR + "team-calendars" + SEPARATOR + CALENDAR_ID_PARAM;
    private static final String RESOURCE_PATH = DOMAIN_PATH + SEPARATOR + "resources" + SEPARATOR + CALENDAR_ID_PARAM;
    private static final String EVENT_COUNT_SUFFIX = SEPARATOR + "eventCount";

    private static final String ACTION_QUERY_PARAM = "action";
    private static final String EXPORT_ACTION = "export";
    private static final String IMPORT_ACTION = "import";
    private static final String ICS_CONTENT_TYPE = "text/calendar; charset=utf-8";
    private static final String ICS_CONTENT_DISPOSITION = "attachment; filename=calendar.ics";

    private static final String FIELD_TASK_ID = "taskId";
    private static final String FIELD_COUNT = "count";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** A calendar a domain owns, resolved from the request path. */
    private record DomainCalendar(Domain domain, OpenPaaSId domainId, CalendarType calendarType, CalendarURL calendarURL) {
    }

    /**
     * Resolves the calendar a request targets. {@code onlyActive} rejects the calendars that may be read but
     * no longer written to - resources flagged as deleted - and is thus only set for imports.
     */
    @FunctionalInterface
    private interface CalendarResolver {
        DomainCalendar resolve(Request request, boolean onlyActive);
    }

    private final OpenPaaSDomainDAO domainDAO;
    private final TeamCalendarService teamCalendarService;
    private final ResourceService resourceService;
    private final CalDavClient calDavClient;
    private final CalendarImportService calendarImportService;
    private final TaskManager taskManager;

    @Inject
    public DomainCalendarContentRoutes(OpenPaaSDomainDAO domainDAO, TeamCalendarService teamCalendarService,
                                       ResourceService resourceService, CalDavClient calDavClient,
                                       CalendarImportService calendarImportService, TaskManager taskManager) {
        this.domainDAO = domainDAO;
        this.teamCalendarService = teamCalendarService;
        this.resourceService = resourceService;
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
        service.get(TEAM_CALENDAR_PATH + EVENT_COUNT_SUFFIX, (request, response) -> countEvents(response, teamCalendar(request, !ONLY_ACTIVE)));
        service.post(TEAM_CALENDAR_PATH, (request, response) -> exportOrImport(request, response, this::teamCalendar));
        service.get(RESOURCE_PATH + EVENT_COUNT_SUFFIX, (request, response) -> countEvents(response, resourceCalendar(request, !ONLY_ACTIVE)));
        service.post(RESOURCE_PATH, (request, response) -> exportOrImport(request, response, this::resourceCalendar));
    }

    private String countEvents(Response response, DomainCalendar calendar) {
        long count = wrapDavErrors(() -> calDavClient.findUserCalendarEventIds(calendar.domainId(), calendar.calendarURL())
            .count()
            .block());

        response.status(HttpStatus.OK_200);
        response.type(Constants.JSON_CONTENT_TYPE);
        return OBJECT_MAPPER.createObjectNode()
            .put(FIELD_COUNT, count)
            .toString();
    }

    private String exportOrImport(Request request, Response response, CalendarResolver calendarResolver) {
        String action = StringUtils.trimToEmpty(request.queryParams(ACTION_QUERY_PARAM)).toLowerCase(Locale.US);

        return switch (action) {
            case EXPORT_ACTION -> exportCalendar(response, calendarResolver.resolve(request, !ONLY_ACTIVE));
            case IMPORT_ACTION -> importCalendar(request, response, calendarResolver.resolve(request, ONLY_ACTIVE));
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

        TaskId taskId = taskManager.submit(new DomainCalendarImportTask(calendarImportService, calendar.domain(),
            calendar.domainId(), calendar.calendarType(), calendar.calendarURL(), events));

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

    /** Team calendars know no deleted state: they are readable and writable as long as they exist. */
    private DomainCalendar teamCalendar(Request request, boolean onlyActive) {
        Domain domain = parseDomain(request);
        TeamCalendarId teamCalendarId = new TeamCalendarId(request.params(CALENDAR_ID_PARAM));

        TeamCalendar teamCalendar = teamCalendarService.retrieve(domain, teamCalendarId)
            .onErrorMap(DomainNotFoundException.class, e -> notFound(e.getMessage()))
            .onErrorMap(TeamCalendarNotFoundException.class, e -> notFound("Team calendar does not exist"))
            .block();

        return new DomainCalendar(domain, teamCalendar.domain().id(), CalendarType.TEAM_CALENDAR,
            CalendarURL.from(teamCalendar.id().asOpenPaaSId()));
    }

    /**
     * Resources flagged as deleted keep their calendar: their content stays readable, but may no longer be
     * written to.
     */
    private DomainCalendar resourceCalendar(Request request, boolean onlyActive) {
        Domain domain = parseDomain(request);
        OpenPaaSDomain openPaaSDomain = domainDAO.retrieve(domain)
            .blockOptional()
            .orElseThrow(() -> notFound("Domain not found: %s".formatted(domain.asString())));
        ResourceId resourceId = new ResourceId(request.params(CALENDAR_ID_PARAM));

        return resourceService.retrieve(resourceId, openPaaSDomain.id(), onlyActive)
            .blockOptional()
            .map(resource -> new DomainCalendar(domain, openPaaSDomain.id(), CalendarType.RESOURCE,
                CalendarURL.from(resource.id().asOpenPaaSId())))
            .orElseThrow(() -> notFound("Resource does not exist"));
    }

    private Domain parseDomain(Request request) {
        String domainName = request.params(DOMAIN_PARAM);
        try {
            return Domain.of(domainName);
        } catch (IllegalArgumentException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid domain: %s", domainName)
                .cause(e)
                .haltError();
        }
    }

    private HaltException notFound(String message) {
        return ErrorResponder.builder()
            .statusCode(HttpStatus.NOT_FOUND_404)
            .type(ErrorResponder.ErrorType.NOT_FOUND)
            .message(message)
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
