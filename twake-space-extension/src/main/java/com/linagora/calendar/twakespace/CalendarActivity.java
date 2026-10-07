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

package com.linagora.calendar.twakespace;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.linagora.calendar.amqp.CalendarEventDeserializeException;
import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO;
import com.linagora.calendar.amqp.EventCalendarNotificationConsumer;
import com.linagora.calendar.amqp.EventEmailConsumer;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.dto.CalendarMirrorSource;
import com.linagora.calendar.dav.dto.CalendarReportJsonResponse;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.TeamCalendarRepository;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.linagora.calendar.storage.model.TeamCalendarId;
import com.linagora.calendar.twakespace.model.ActivityEvent;
import com.linagora.calendar.twakespace.model.ActivityEvent.EventAction;
import com.linagora.calendar.twakespace.model.ActivityEvent.EventChange;
import com.linagora.calendar.twakespace.model.CalendarEventSnapshot;
import com.linagora.calendar.twakespace.model.OrganizationId;
import com.linagora.calendar.twakespace.model.SpaceId;
import com.linagora.calendar.twakespace.storage.TwakeSpaceRepository;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.property.Method;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// Sabre names a team calendar only on the copy of a write it sends for the team calendar's own instance, at
// /calendars/{teamCalendarId}/{teamCalendarId}/: the writes are read from that copy, along with the version they replace.
// An attendee outside the space answers from their own calendar: their answer is read from the mail sabre sends the
// organizer, and the event from the team calendar that has it.
public class CalendarActivity {
    static final String CREATED_EXCHANGE = EventCalendarNotificationConsumer.Queue.ADD.exchangeName();
    static final String UPDATED_EXCHANGE = EventCalendarNotificationConsumer.Queue.UPDATE.exchangeName();
    static final String EMAIL_EXCHANGE = EventEmailConsumer.EXCHANGE_NAME;
    public static final List<String> EXCHANGES = List.of(CREATED_EXCHANGE, UPDATED_EXCHANGE, EMAIL_EXCHANGE);

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarActivity.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String PRINCIPAL_PREFIX = "principals/users/";

    private record EventWrite(boolean created, OrganizationId organization, String actor, CalendarEventSnapshot event,
                              Optional<CalendarEventSnapshot> previous, String etag) {
    }

    private record EventAnswer(OrganizationId organization, MailAddress attendee, CalendarEventSnapshot event, VEvent answered) {
    }

    private final TeamCalendarRepository teamCalendarRepository;
    private final TwakeSpaceRepository spaceRepository;
    private final OpenPaaSUserDAO userDAO;
    private final CalDavClient calDavClient;
    private final Clock clock;

    @Inject
    public CalendarActivity(TeamCalendarRepository teamCalendarRepository, TwakeSpaceRepository spaceRepository, OpenPaaSUserDAO userDAO,
                            CalDavClient calDavClient, Clock clock) {
        this.teamCalendarRepository = teamCalendarRepository;
        this.spaceRepository = spaceRepository;
        this.userDAO = userDAO;
        this.calDavClient = calDavClient;
        this.clock = clock;
    }

    public Mono<ActivityEvent> handle(String exchange, byte[] body) {
        return Mono.defer(() -> {
            JsonNode message = readTree(body);
            if (EMAIL_EXCHANGE.equals(exchange)) {
                return answer(message, body);
            }
            return write(CREATED_EXCHANGE.equals(exchange), message);
        });
    }

    private Mono<ActivityEvent> write(boolean created, JsonNode message) {
        String eventPath = message.path("eventPath").asText();
        Optional<CalendarURL> ownInstance = ownInstance(eventPath);
        if (ownInstance.isEmpty() || message.path("import").asBoolean()) {
            return Mono.empty();
        }
        CalendarURL calendar = ownInstance.get();
        String resourceName = StringUtils.substringAfterLast(eventPath, "/");
        return organization(TeamCalendarId.from(calendar.calendarId()))
            .flatMap(organization -> Mono.justOrEmpty(CalendarEventSnapshot.fromJCal(calendar, resourceName, message.path("event")))
                .flatMap(event -> actor(message.path("connectedUser").asText(), event)
                    .map(actor -> new EventWrite(created, organization, actor, event,
                        previousVersion(message.path("old_event"), calendar, resourceName), message.path("etag").asText(event.uid())))))
            .flatMap(write -> Mono.justOrEmpty(writeActivity(write)));
    }

    private Optional<ActivityEvent> writeActivity(EventWrite write) {
        CalendarEventSnapshot event = write.event();
        if (write.created()) {
            return Optional.of(activity(new EventChange(EventAction.CREATED, write.organization(), write.actor(), event, emptyState(),
                attendeesBut(event, write.actor()), write.etag())));
        }
        if (write.previous().isEmpty()) {
            return Optional.of(updated(write));
        }
        CalendarEventSnapshot previous = write.previous().get();
        if (event.equals(previous)) {
            return Optional.empty();
        }
        if (event.rescheduledFrom(previous)) {
            ObjectNode state = emptyState();
            ObjectNode previousTime = state.putObject("previous")
                .put("start", ActivityEvent.time(previous.start(), previous.allDay()));
            previous.end().ifPresent(end -> previousTime.put("end", ActivityEvent.time(end, previous.allDay())));
            return Optional.of(activity(new EventChange(EventAction.RESCHEDULED, write.organization(), write.actor(), event, state,
                attendeesBut(event, write.actor()), write.etag())));
        }
        if (event.onlyPartStatsChangedFrom(previous)) {
            // Changes of other attendees' answers are sabre applying their reply, published from their reply.
            return Optional.ofNullable(event.partStatChangesFrom(previous).get(StringUtils.lowerCase(write.actor())))
                .map(partStat -> activity(new EventChange(answerAction(partStat), write.organization(), write.actor(), event, emptyState(),
                    organizerBut(event, write.actor()), write.etag())));
        }
        return Optional.of(updated(write));
    }

    private ActivityEvent updated(EventWrite write) {
        return activity(new EventChange(EventAction.UPDATED, write.organization(), write.actor(), write.event(), emptyState(), List.of(),
            write.etag()));
    }

    // An answer to a single occurrence publishes nothing: a card shows the whole event.
    private Mono<ActivityEvent> answer(JsonNode message, byte[] body) {
        String method = message.path("method").asText();
        if (!Method.VALUE_REPLY.equals(method) && !Method.VALUE_COUNTER.equals(method)) {
            return Mono.empty();
        }
        CalendarEventNotificationEmailDTO mail = readMail(body);
        Optional<VEvent> answered = mail.event().<VEvent>getComponents(Component.VEVENT).stream()
            .filter(event -> event.getProperty(Property.RECURRENCE_ID).isEmpty())
            .findFirst();
        Optional<String> uid = answered.flatMap(VEvent::getUid).map(Property::getValue);
        if (uid.isEmpty()) {
            return Mono.empty();
        }
        return teamCalendarEvent(Username.fromMailAddress(mail.recipientEmail()), uid.get())
            .flatMap(event -> organization(event.teamCalendarId())
                .map(organization -> new EventAnswer(organization, mail.senderEmail(), event, answered.get())))
            .flatMap(answer -> Mono.justOrEmpty(Method.VALUE_COUNTER.equals(method) ? Optional.of(proposed(answer)) : reply(answer)));
    }

    // Sabre finds no event by UID in the organizer's instance of a team calendar, only in the team calendar itself.
    private Mono<CalendarEventSnapshot> teamCalendarEvent(Username organizer, String uid) {
        return userDAO.retrieve(organizer)
            .flatMap(calDavClient::findUserCalendarList)
            .flatMapMany(calendars -> Flux.fromIterable(calendars.calendars().values()))
            .flatMap(metadata -> Mono.justOrEmpty(CalendarMirrorSource.parse(metadata).delegatedSource()))
            .filterWhen(source -> teamCalendarRepository.retrieve(TeamCalendarId.from(source.calendarId())).hasElement())
            .concatMap(teamCalendar -> calDavClient.calendarReportByUid(organizer, teamCalendar.base(), uid)
                .map(CalendarReportJsonResponse::calendarHref)
                .flatMap(href -> calDavClient.fetchCalendarEvent(organizer, href)
                    .flatMap(object -> Mono.justOrEmpty(masterEvent(object.calendarData(), teamCalendar,
                        StringUtils.substringAfterLast(href.getPath(), "/")))))
                .map(event -> CalendarEventSnapshot.from(TeamCalendarId.from(teamCalendar.calendarId()), event)))
            .next();
    }

    // Sabre sends the mail before it writes the answer to the event of the team calendar.
    private Optional<ActivityEvent> reply(EventAnswer answer) {
        String attendee = answer.attendee().asString();
        String dtStamp = answer.answered().getProperty(Property.DTSTAMP).map(Property::getValue).orElse("");
        return EventParseUtils.findAttendeePartStat(answer.answered(), answer.attendee())
            .map(PartStat::getValue)
            .map(partStat -> {
                CalendarEventSnapshot answered = answer.event().withPartStat(attendee, partStat);
                return activity(new EventChange(answerAction(partStat), answer.organization(), attendee, answered, emptyState(),
                    organizerBut(answered, attendee), String.join(":", attendee, partStat, dtStamp)));
            });
    }

    private ActivityEvent proposed(EventAnswer answer) {
        VEvent counter = answer.answered();
        boolean allDay = EventParseUtils.isAllDay(counter);
        String start = ActivityEvent.time(EventParseUtils.getStartTime(counter).toInstant(), allDay);
        String attendee = answer.attendee().asString();
        ObjectNode state = emptyState();
        ObjectNode proposed = state.putObject("proposed")
            .put("start", start);
        Optional<String> end = EventParseUtils.getEndTime(counter).map(time -> ActivityEvent.time(time.toInstant(), allDay));
        end.ifPresent(value -> proposed.put("end", value));
        proposed.put("by", attendee);
        return activity(new EventChange(EventAction.PROPOSED, answer.organization(), attendee, answer.event(), state,
            organizerBut(answer.event(), attendee), String.join(":", attendee, start, end.orElse(""))));
    }

    private ActivityEvent activity(EventChange change) {
        return ActivityEvent.calendarEvent(change, clock.instant());
    }

    // The activity of a space that is deleted, or that the extension does not know, is no space's.
    private Mono<OrganizationId> organization(TeamCalendarId teamCalendarId) {
        return teamCalendarRepository.retrieve(teamCalendarId)
            .flatMap(teamCalendar -> spaceRepository.retrieve(new SpaceId(teamCalendar.name())))
            .filter(space -> space.deletion().isEmpty())
            .flatMap(space -> Mono.justOrEmpty(space.organization()));
    }

    // A write made with a technical token has no user: the organizer stands for the actor.
    private Mono<String> actor(String connectedUser, CalendarEventSnapshot event) {
        return Mono.justOrEmpty(StringUtils.removeStart(connectedUser, PRINCIPAL_PREFIX))
            .filter(userId -> connectedUser.startsWith(PRINCIPAL_PREFIX) && !userId.isBlank())
            .flatMap(userId -> userDAO.retrieve(new OpenPaaSId(userId)))
            .map(user -> user.username().asString())
            .switchIfEmpty(Mono.justOrEmpty(event.organizer()))
            .switchIfEmpty(Mono.fromRunnable(() ->
                LOGGER.info("Ignoring a change of event {}: neither its author nor its organizer is known", event.uid())));
    }

    private static Optional<CalendarURL> ownInstance(String eventPath) {
        try {
            return Optional.of(CalendarURL.parse(eventPath))
                .filter(calendar -> calendar.base().equals(calendar.calendarId()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // Without the version a write replaces, the write is told as an update.
    private static Optional<CalendarEventSnapshot> previousVersion(JsonNode oldEvent, CalendarURL calendar, String resourceName) {
        if (!oldEvent.isArray()) {
            return Optional.empty();
        }
        try {
            return CalendarEventSnapshot.fromJCal(calendar, resourceName, oldEvent);
        } catch (RuntimeException e) {
            LOGGER.warn("Ignoring the previous version of event {} of team calendar {} that can not be read",
                resourceName, calendar.calendarId().value(), e);
            return Optional.empty();
        }
    }

    private static Optional<EventFields> masterEvent(Calendar calendarData, CalendarURL calendar, String resourceName) {
        List<VEvent> events = calendarData.getComponents(Component.VEVENT);
        return events.stream()
            .filter(event -> event.getProperty(Property.RECURRENCE_ID).isEmpty())
            .findFirst()
            .or(() -> events.stream().findFirst())
            .map(event -> EventFields.fromVEvent(event, calendar, resourceName));
    }

    private static EventAction answerAction(String partStat) {
        return switch (partStat) {
            case CalendarEventSnapshot.ACCEPTED -> EventAction.ACCEPTED;
            case CalendarEventSnapshot.DECLINED -> EventAction.DECLINED;
            default -> EventAction.UPDATED;
        };
    }

    private static List<String> attendeesBut(CalendarEventSnapshot event, String actor) {
        return event.attendees().keySet().stream()
            .filter(attendee -> !attendee.equalsIgnoreCase(actor))
            .sorted()
            .toList();
    }

    private static List<String> organizerBut(CalendarEventSnapshot event, String actor) {
        return event.organizer()
            .filter(organizer -> !organizer.equalsIgnoreCase(actor))
            .stream()
            .toList();
    }

    private static ObjectNode emptyState() {
        return OBJECT_MAPPER.createObjectNode();
    }

    private static JsonNode readTree(byte[] body) {
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (IOException e) {
            throw new CalendarEventDeserializeException("Unable to read a calendar event message", e);
        }
    }

    private static CalendarEventNotificationEmailDTO readMail(byte[] body) {
        try {
            return OBJECT_MAPPER.readValue(body, CalendarEventNotificationEmailDTO.class);
        } catch (IOException e) {
            throw new CalendarEventDeserializeException("Unable to read a calendar mail message", e);
        }
    }
}
