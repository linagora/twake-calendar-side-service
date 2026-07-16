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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.james.core.Username;
import org.apache.james.task.TaskId;
import org.apache.james.task.TaskManager;
import org.apache.james.util.ValuePatch;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.base.Preconditions;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.restapi.routes.BookingLinkCreateRoute.CreateBookingLinkRequestDTO;
import com.linagora.calendar.restapi.routes.BookingLinkExtraAttendeeResolver;
import com.linagora.calendar.restapi.routes.BookingLinkPatchRoute.PatchDto;
import com.linagora.calendar.restapi.routes.dto.BookingLinkDTO;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkColorUtil;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkExtraAttendeeUtil;
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;
import com.linagora.calendar.storage.booking.BookingLinkNotFoundException;
import com.linagora.calendar.storage.booking.BookingLinkPatchRequest;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;
import com.linagora.calendar.webadmin.service.BookingLinkEventDeletionService;
import com.linagora.calendar.webadmin.task.BookingLinkEventDeletionTask;

import reactor.core.publisher.Mono;
import spark.HaltException;
import spark.Request;
import spark.Response;
import spark.Service;

/**
 * Administrative CRUD over the booking links of a given user. Mirrors the
 * end-user {@code /api/booking-links} API but is scoped to an explicit
 * {@code :username} path parameter and delegates to {@link BookingLinkDAO}.
 *
 * <p>Unlike the end-user API, no per-user settings resolution happens here:
 * availability rules without an explicit {@code timeZone} are interpreted in
 * UTC, and an omitted {@code availabilityRules} on creation stores no rule.
 */
public class BookingLinkUserRoutes implements Routes {

    public static final String BASE_PATH = "/users";

    private static final String USERNAME_PARAM = ":username";
    private static final String PUBLIC_ID_PARAM = ":publicId";
    private static final String BOOKING_LINKS_PATH = BASE_PATH + SEPARATOR + USERNAME_PARAM + SEPARATOR + "booking-links";
    private static final String BOOKING_LINK_PATH = BOOKING_LINKS_PATH + SEPARATOR + PUBLIC_ID_PARAM;
    private static final String RESET_PATH = BOOKING_LINK_PATH + SEPARATOR + "reset";

    private static final String ACTION_PARAM = "action";
    private static final String SINCE_PARAM = "since";
    private static final String DELETE_EVENTS_ACTION = "deleteEvents";
    private static final String FIELD_TASK_ID = "taskId";

    private static final String FIELD_CALENDAR_URL = "calendarUrl";
    private static final String FIELD_DURATION_MINUTES = "durationMinutes";
    private static final String FIELD_ACTIVE = "active";
    private static final String FIELD_AUTO_ACCEPT = "autoAccept";
    private static final String FIELD_AVAILABILITY_RULES = "availabilityRules";
    private static final String FIELD_EXTRA_ATTENDEES = "extraAttendees";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_COLOR = "color";
    private static final String FIELD_PUBLIC_ID = "bookingLinkPublicId";
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("UTC");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    private final OpenPaaSUserDAO userDAO;
    private final BookingLinkDAO bookingLinkDAO;
    private final CalDavClient calDavClient;
    private final TaskManager taskManager;
    private final BookingLinkEventDeletionService eventDeletionService;
    private final BookingLinkExtraAttendeeResolver extraAttendeeResolver;
    private final JsonTransformer jsonTransformer;

    @Inject
    public BookingLinkUserRoutes(OpenPaaSUserDAO userDAO,
                                 BookingLinkDAO bookingLinkDAO,
                                 CalDavClient calDavClient,
                                 TaskManager taskManager,
                                 BookingLinkEventDeletionService eventDeletionService,
                                 BookingLinkExtraAttendeeResolver extraAttendeeResolver,
                                 JsonTransformer jsonTransformer) {
        this.userDAO = userDAO;
        this.bookingLinkDAO = bookingLinkDAO;
        this.calDavClient = calDavClient;
        this.taskManager = taskManager;
        this.eventDeletionService = eventDeletionService;
        this.extraAttendeeResolver = extraAttendeeResolver;
        this.jsonTransformer = jsonTransformer;
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.get(BOOKING_LINKS_PATH, this::listBookingLinks, jsonTransformer);
        service.post(BOOKING_LINKS_PATH, this::createBookingLink, jsonTransformer);
        service.get(BOOKING_LINK_PATH, this::getBookingLink, jsonTransformer);
        service.patch(BOOKING_LINK_PATH, this::updateBookingLink);
        service.delete(BOOKING_LINK_PATH, this::deleteBookingLink);
        service.post(BOOKING_LINK_PATH, this::postBookingLink, jsonTransformer);
        service.post(RESET_PATH, this::resetPublicId, jsonTransformer);
    }

    private Map<String, String> postBookingLink(Request request, Response response) {
        String action = request.queryParams(ACTION_PARAM);
        if (DELETE_EVENTS_ACTION.equals(action)) {
            return submitDeleteEventsTask(request, response);
        }
        throw ErrorResponder.builder()
            .statusCode(HttpStatus.BAD_REQUEST_400)
            .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
            .message("Invalid action: '%s'. Supported actions are: %s".formatted(action, DELETE_EVENTS_ACTION))
            .haltError();
    }

    private Map<String, String> submitDeleteEventsTask(Request request, Response response) {
        Username username = retrieveUser(request).username();
        BookingLinkPublicId publicId = parsePublicId(request);
        Optional<Instant> since = parseSince(request);

        BookingLink bookingLink = bookingLinkDAO.findByPublicId(username, publicId)
            .blockOptional()
            .orElseThrow(() -> bookingLinkNotFound(publicId));

        BookingLinkEventDeletionTask task = new BookingLinkEventDeletionTask(eventDeletionService,
            username, bookingLink.calendarUrl(), publicId, since);
        TaskId taskId = taskManager.submit(task);

        response.status(HttpStatus.CREATED_201);
        return Map.of(FIELD_TASK_ID, taskId.asString());
    }

    private Optional<Instant> parseSince(Request request) {
        return Optional.ofNullable(request.queryParams(SINCE_PARAM))
            .filter(StringUtils::isNotBlank)
            .map(value -> {
                try {
                    return Instant.parse(value);
                } catch (DateTimeParseException e) {
                    throw badRequest("Invalid 'since' query parameter, expecting an ISO-8601 instant: %s".formatted(value), e);
                }
            });
    }

    private List<BookingLinkDTO> listBookingLinks(Request request, Response response) {
        Username username = retrieveUser(request).username();

        return bookingLinkDAO.findByUsername(username)
            .map(BookingLinkDTO::from)
            .collectList()
            .block();
    }

    private BookingLinkDTO getBookingLink(Request request, Response response) {
        Username username = retrieveUser(request).username();
        BookingLinkPublicId publicId = parsePublicId(request);

        return bookingLinkDAO.findByPublicId(username, publicId)
            .map(BookingLinkDTO::from)
            .blockOptional()
            .orElseThrow(() -> bookingLinkNotFound(publicId));
    }

    private Map<String, String> createBookingLink(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        BookingLinkInsertRequest insertRequest = parseInsertRequest(request);

        BookingLink bookingLink = validateCalendarAccess(user.username(), insertRequest.calendarUrl())
            .then(validateExtraAttendees(user.username(), insertRequest.extraAttendees()))
            .then(bookingLinkDAO.insert(user.username(), insertRequest))
            .block();

        response.header(HttpHeader.LOCATION.asString(),
            BASE_PATH + SEPARATOR + user.username().asString() + "/booking-links/" + bookingLink.publicId().value());
        response.status(HttpStatus.CREATED_201);
        return Map.of(FIELD_PUBLIC_ID, bookingLink.publicId().value().toString());
    }

    private String updateBookingLink(Request request, Response response) {
        Username username = retrieveUser(request).username();
        BookingLinkPublicId publicId = parsePublicId(request);
        BookingLinkPatchRequest patchRequest = parsePatchRequest(request);

        Mono<Void> validateCalendar = patchRequest.calendarUrl().toOptional()
            .map(calendarURL -> validateCalendarAccess(username, calendarURL))
            .orElse(Mono.empty());

        validateCalendar
            .then(validateExtraAttendees(username, patchRequest.extraAttendees().getOrElse(List.of())))
            .then(bookingLinkDAO.update(username, publicId, patchRequest))
            .onErrorMap(BookingLinkNotFoundException.class, e -> bookingLinkNotFound(publicId))
            .block();

        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
    }

    private String deleteBookingLink(Request request, Response response) {
        Username username = retrieveUser(request).username();
        BookingLinkPublicId publicId = parsePublicId(request);

        bookingLinkDAO.findByPublicId(username, publicId)
            .switchIfEmpty(Mono.error(() -> bookingLinkNotFound(publicId)))
            .flatMap(existing -> bookingLinkDAO.delete(username, publicId))
            .block();

        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
    }

    private Map<String, String> resetPublicId(Request request, Response response) {
        Username username = retrieveUser(request).username();
        BookingLinkPublicId publicId = parsePublicId(request);

        BookingLinkPublicId newPublicId = bookingLinkDAO.resetPublicId(username, publicId)
            .onErrorMap(BookingLinkNotFoundException.class, e -> bookingLinkNotFound(publicId))
            .block();

        response.status(HttpStatus.OK_200);
        return Map.of(FIELD_PUBLIC_ID, newPublicId.value().toString());
    }

    private BookingLinkInsertRequest parseInsertRequest(Request request) {
        try {
            CreateBookingLinkRequestDTO dto = OBJECT_MAPPER.readValue(request.bodyAsBytes(), CreateBookingLinkRequestDTO.class);
            return CreateBookingLinkRequestDTO.toBookingLinkInsertRequest(dto, DEFAULT_ZONE, Optional.empty());
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage(), e);
        } catch (Exception e) {
            throw invalidBody(e);
        }
    }

    private BookingLinkPatchRequest parsePatchRequest(Request request) {
        try {
            byte[] body = request.bodyAsBytes();
            JsonNode node = OBJECT_MAPPER.readTree(body);
            PatchDto dto = OBJECT_MAPPER.readValue(body, PatchDto.class);
            return new BookingLinkPatchRequest(
                parseCalendarUrl(node, dto),
                parseDuration(node, dto),
                parseActive(node, dto),
                parseAutoAccept(node, dto),
                parseAvailabilityRules(node, dto),
                parseExtraAttendees(node, dto),
                parseName(node, dto),
                parseDescription(node, dto),
                parseColor(node, dto));
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage(), e);
        } catch (Exception e) {
            throw invalidBody(e);
        }
    }

    private ValuePatch<CalendarURL> parseCalendarUrl(JsonNode node, PatchDto dto) {
        if (!node.has(FIELD_CALENDAR_URL)) {
            return ValuePatch.keep();
        }
        return dto.calendarUrl().map(CalendarURL::parse)
            .map(ValuePatch::modifyTo)
            .orElseThrow(() -> new IllegalArgumentException("'calendarUrl' cannot be removed"));
    }

    private ValuePatch<Duration> parseDuration(JsonNode node, PatchDto dto) {
        if (!node.has(FIELD_DURATION_MINUTES)) {
            return ValuePatch.keep();
        }
        return dto.durationMinutes().map(durationMinutes -> {
                Preconditions.checkArgument(durationMinutes > 0, "'durationMinutes' must be positive");
                return Duration.ofMinutes(durationMinutes);
            }).map(ValuePatch::modifyTo)
            .orElseThrow(() -> new IllegalArgumentException("'durationMinutes' cannot be removed"));
    }

    private ValuePatch<Boolean> parseActive(JsonNode node, PatchDto dto) {
        if (!node.has(FIELD_ACTIVE)) {
            return ValuePatch.keep();
        }
        return dto.active().map(ValuePatch::modifyTo)
            .orElseThrow(() -> new IllegalArgumentException("'active' cannot be removed"));
    }

    private ValuePatch<Boolean> parseAutoAccept(JsonNode node, PatchDto dto) {
        if (!node.has(FIELD_AUTO_ACCEPT)) {
            return ValuePatch.keep();
        }
        return dto.autoAccept().map(ValuePatch::modifyTo)
            .orElseThrow(() -> new IllegalArgumentException("'autoAccept' cannot be removed"));
    }

    private ValuePatch<AvailabilityRules> parseAvailabilityRules(JsonNode node, PatchDto dto) {
        if (!node.has(FIELD_AVAILABILITY_RULES)) {
            return ValuePatch.keep();
        }
        return dto.availabilityRules().map(rules -> rules.stream()
                .map(availabilityRuleDTO -> availabilityRuleDTO.toAvailabilityRule(DEFAULT_ZONE))
                .toList())
            .map(ruleList -> {
                Preconditions.checkArgument(!ruleList.isEmpty(), "'availabilityRules' cannot be empty if provided");
                return new AvailabilityRules(ruleList);
            })
            .map(ValuePatch::modifyTo)
            .orElseGet(ValuePatch::remove);
    }

    private ValuePatch<List<OpenPaaSId>> parseExtraAttendees(JsonNode node, PatchDto dto) {
        if (!node.has(FIELD_EXTRA_ATTENDEES)) {
            return ValuePatch.keep();
        }
        return dto.extraAttendees().map(BookingLinkExtraAttendeeUtil::parse)
            .filter(extraAttendees -> !extraAttendees.isEmpty())
            .map(ValuePatch::modifyTo)
            .orElseGet(ValuePatch::remove);
    }

    private ValuePatch<String> parseName(JsonNode node, PatchDto dto) {
        if (!node.has(FIELD_NAME)) {
            return ValuePatch.keep();
        }
        return dto.name().map(String::trim).filter(name -> !name.isEmpty())
            .map(ValuePatch::modifyTo)
            .orElseGet(ValuePatch::remove);
    }

    private ValuePatch<String> parseDescription(JsonNode node, PatchDto dto) {
        if (!node.has(FIELD_DESCRIPTION)) {
            return ValuePatch.keep();
        }
        return dto.description().map(String::trim).filter(description -> !description.isEmpty())
            .map(ValuePatch::modifyTo)
            .orElseGet(ValuePatch::remove);
    }

    private ValuePatch<String> parseColor(JsonNode node, PatchDto dto) {
        if (!node.has(FIELD_COLOR)) {
            return ValuePatch.keep();
        }
        return BookingLinkColorUtil.sanitize(dto.color())
            .map(ValuePatch::modifyTo)
            .orElseGet(ValuePatch::remove);
    }

    private Mono<Void> validateExtraAttendees(Username username, List<OpenPaaSId> extraAttendees) {
        return extraAttendeeResolver.validate(username, extraAttendees)
            .onErrorMap(IllegalArgumentException.class, e -> badRequest(e.getMessage(), e));
    }

    private Mono<Void> validateCalendarAccess(Username username, CalendarURL calendarURL) {
        return calDavClient.resolveCalendarAccess(username, calendarURL)
            .flatMap(access -> switch (access) {
                case WRITABLE -> Mono.<Void>empty();
                case READ_ONLY -> Mono.error(forbidden("User does not have write access to calendar: " + calendarURL.asUri()));
                case NOT_FOUND -> Mono.error(badRequest("Calendar not found or access denied: " + calendarURL.asUri(), null));
            });
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

    private BookingLinkPublicId parsePublicId(Request request) {
        String rawPublicId = request.params(PUBLIC_ID_PARAM);
        try {
            return new BookingLinkPublicId(UUID.fromString(rawPublicId));
        } catch (IllegalArgumentException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid booking link public id: %s", rawPublicId)
                .cause(e)
                .haltError();
        }
    }

    private HaltException bookingLinkNotFound(BookingLinkPublicId publicId) {
        return ErrorResponder.builder()
            .statusCode(HttpStatus.NOT_FOUND_404)
            .type(ErrorResponder.ErrorType.NOT_FOUND)
            .message("Booking link does not exist: %s", publicId.value())
            .haltError();
    }

    private HaltException invalidBody(Exception e) {
        String detail = Optional.ofNullable(ExceptionUtils.getRootCause(e))
            .map(Throwable::getMessage)
            .orElse(e.getMessage());
        return badRequest("Invalid request body: %s".formatted(detail), e);
    }

    private HaltException forbidden(String message) {
        return ErrorResponder.builder()
            .statusCode(HttpStatus.FORBIDDEN_403)
            .type(ErrorResponder.ErrorType.WRONG_STATE)
            .message(StringUtils.defaultString(message, "Forbidden"))
            .haltError();
    }

    private HaltException badRequest(String message, Exception cause) {
        var builder = ErrorResponder.builder()
            .statusCode(HttpStatus.BAD_REQUEST_400)
            .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
            .message(StringUtils.defaultString(message, "Invalid request"));
        if (cause != null) {
            builder = builder.cause(cause);
        }
        return builder.haltError();
    }
}
