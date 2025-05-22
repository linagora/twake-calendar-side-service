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

package com.linagora.calendar.restapi.routes;

import static java.time.format.DateTimeFormatter.ISO_INSTANT;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.mail.internet.AddressException;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.vacation.api.AccountId;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;
import com.linagora.calendar.storage.eventsearch.EventFields;
import com.linagora.calendar.storage.eventsearch.EventSearchQuery;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.QueryStringDecoder;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class CalendarSearchRoute extends CalendarRoute {

    public static class SearchRequest {
        public final List<CalendarRef> calendars;
        public final String query;
        public final List<String> organizers;
        public final List<String> attendees;

        @JsonCreator
        public SearchRequest(@JsonProperty("calendars") List<CalendarRef> calendars,
                             @JsonProperty("query") String query,
                             @JsonProperty("organizers") List<String> organizers,
                             @JsonProperty("attendees") List<String> attendees) {
            this.calendars = calendars;
            this.query = query;
            this.organizers = organizers;
            this.attendees = attendees;
        }

        public static class CalendarRef {
            public final String userId;
            public final String calendarId;

            @JsonCreator
            public CalendarRef(@JsonProperty("userId") String userId,
                               @JsonProperty("calendarId") String calendarId) {
                this.userId = userId;
                this.calendarId = calendarId;
            }
        }
    }

    public static class SearchResponse {
        @JsonProperty("_links")
        public final Links links;
        @JsonProperty("_total_hits")
        public final int totalHits;
        @JsonProperty("_embedded")
        public final Embedded embedded;

        public SearchResponse(Links links, int totalHits, Embedded embedded) {
            this.links = links;
            this.totalHits = totalHits;
            this.embedded = embedded;
        }

        public static SearchResponse from(List<EventFields> events, int limit, int offset, String uri) {
            List<EventResource> eventResources = events.stream()
                .map(EventResource::from)
                .collect(Collectors.toList());
            return new SearchResponse(new Links(new Link(uri + "?limit=" + limit + "&offset=" + offset)),
                events.size(),
                new Embedded(eventResources));
        }

        public static class Links {
            @JsonProperty("self")
            public final Link self;

            public Links(Link self) {
                this.self = self;
            }
        }

        public static class Link {
            @JsonProperty("href")
            public final String href;

            public Link(String href) {
                this.href = href;
            }
        }

        public static class Embedded {
            @JsonProperty("events")
            public final List<EventResource> events;

            public Embedded(List<EventResource> events) {
                this.events = events;
            }
        }

        public static class EventResource {
            @JsonProperty("_links")
            public final Links links;
            @JsonProperty("data")
            public final Data data;

            public EventResource(Links links, Data data) {
                this.links = links;
                this.data = data;
            }

            public static EventResource from(EventFields event) {
                String userId = event.calendarURL().base().value();
                String calendarId = event.calendarURL().calendarId().value();
                String href = "/calendars/" + userId + "/" + calendarId + "/" + event.uid().value() + ".ics";
                return new EventResource(new Links(new Link(href)),
                    Data.from(event, userId, calendarId));
            }

            public static class Data {
                public final String uid;
                public final String summary;
                public final String description;
                public final String start;
                public final String end;
                @JsonProperty("class")
                public final String clazz;
                public final Boolean allDay;
                public final Boolean hasResources;
                public final Integer durationInDays;
                public final Boolean isRecurrentMaster;
                public final List<Attendee> attendees;
                public final Organizer organizer;
                public final String userId;
                public final String calendarId;
                public final String dtstamp;

                public Data(String uid, String summary, String description, String start, String end, String clazz,
                            Boolean allDay, Boolean hasResources, Integer durationInDays, Boolean isRecurrentMaster,
                            List<Attendee> attendees, Organizer organizer, String userId, String calendarId, String dtstamp) {
                    this.uid = uid;
                    this.summary = summary;
                    this.description = description;
                    this.start = start;
                    this.end = end;
                    this.clazz = clazz;
                    this.allDay = allDay;
                    this.hasResources = hasResources;
                    this.durationInDays = durationInDays;
                    this.isRecurrentMaster = isRecurrentMaster;
                    this.attendees = attendees;
                    this.organizer = organizer;
                    this.userId = userId;
                    this.calendarId = calendarId;
                    this.dtstamp = dtstamp;
                }

                public static Data from(EventFields event, String userId, String calendarId) {
                    List<Attendee> attendees = event.attendees().stream()
                        .map(person -> new Attendee(person.email().asString(), person.cn()))
                        .collect(Collectors.toList());
                    Organizer organizer = new Organizer(event.organizer().email().asString(), event.organizer().cn());

                    return new Data(event.uid().value(),
                        event.summary(),
                        event.description(),
                        ISO_INSTANT.format(event.start()),
                        ISO_INSTANT.format(event.end()),
                        event.clazz(),
                        event.allDay(),
                        event.hasResources(),
                        event.durationInDays(),
                        event.isRecurrentMaster(),
                        attendees,
                        organizer,
                        userId,
                        calendarId,
                        ISO_INSTANT.format(event.dtStamp()));
                }

                public static class Attendee {
                    public final String email;
                    public final String cn;

                    public Attendee(String email, String cn) {
                        this.email = email;
                        this.cn = cn;
                    }
                }

                public static class Organizer {
                    public final String email;
                    public final String cn;

                    public Organizer(String email, String cn) {
                        this.email = email;
                        this.cn = cn;
                    }
                }
            }
        }
    }

    public static final String LIMIT_PARAM = "limit";
    public static final String OFFSET_PARAM = "offset";
    public static final int DEFAULT_LIMIT = 30;
    public static final int DEFAULT_OFFSET = 0;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CalendarSearchService searchService;

    @Inject
    public CalendarSearchRoute(Authenticator authenticator, MetricFactory metricFactory,
                               CalendarSearchService searchService) {
        super(authenticator, metricFactory);
        this.searchService = searchService;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/calendar/api/events/search");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
        int limit = extractLimit(queryStringDecoder);
        int offset = extractOffset(queryStringDecoder);

        return request.receive().aggregate().asString()
            .map(Throwing.function(string -> OBJECT_MAPPER.readValue(string, SearchRequest.class)))
            .flatMap(searchRequest -> {
                EventSearchQuery.Builder queryBuilder = EventSearchQuery.builder()
                    .query(extractQuery(searchRequest))
                    .limit(limit)
                    .offset(offset);
                extractCalendarUrls(searchRequest).ifPresent(queryBuilder::calendars);
                extractOrganizers(searchRequest).ifPresent(queryBuilder::organizers);
                extractAttendees(searchRequest).ifPresent(queryBuilder::attendees);
                EventSearchQuery query = queryBuilder.build();

                return searchService.search(AccountId.fromUsername(session.getUser()), query)
                    .collectList()
                    .map(Throwing.function(events -> OBJECT_MAPPER.writeValueAsString(SearchResponse.from(events, limit, offset, request.uri()))))
                    .flatMap(responseBody -> response.status(200)
                        .header("Content-Type", "application/json")
                        .sendString(Mono.just(responseBody))
                        .then());
            });
    }

    private int extractLimit(QueryStringDecoder queryStringDecoder) {
        return queryStringDecoder.parameters().getOrDefault(LIMIT_PARAM, List.of())
            .stream()
            .filter(s -> !s.isBlank())
            .findAny()
            .map(s -> {
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid limit param: " + s);
                }
            }).orElse(DEFAULT_LIMIT);
    }

    private int extractOffset(QueryStringDecoder queryStringDecoder) {
        return queryStringDecoder.parameters().getOrDefault(OFFSET_PARAM, List.of())
            .stream()
            .filter(s -> !s.isBlank())
            .findAny()
            .map(s -> {
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid offset param: " + s);
                }
            }).orElse(DEFAULT_OFFSET);
    }

    private String extractQuery(SearchRequest searchRequest) {
        if (StringUtils.isNotBlank(searchRequest.query)) {
            return searchRequest.query;
        } else {
            throw new IllegalArgumentException("Query field is required");
        }
    }

    private Optional<List<CalendarURL>> extractCalendarUrls(SearchRequest searchRequest) {
        return Optional.ofNullable(searchRequest.calendars)
            .filter(calendars -> !calendars.isEmpty())
            .map(calendars -> calendars.stream()
                .map(calendarRef -> {
                    if (StringUtils.isBlank(calendarRef.userId)) {
                        throw new IllegalArgumentException("userId field is missing");
                    }
                    if (StringUtils.isBlank(calendarRef.calendarId)) {
                        throw new IllegalArgumentException("calendarId field is missing");
                    }
                    return new CalendarURL(new OpenPaaSId(calendarRef.userId), new OpenPaaSId(calendarRef.calendarId));
                })
                .collect(Collectors.toList()));
    }

    private Optional<List<MailAddress>> extractOrganizers(SearchRequest searchRequest) {
        return Optional.ofNullable(searchRequest.organizers)
            .filter(organizers -> !organizers.isEmpty())
            .map(organizers -> organizers.stream()
                .map(organizer -> {
                    try {
                        return new MailAddress(organizer);
                    } catch (AddressException e) {
                        throw new IllegalArgumentException("Invalid organizer email address", e);
                    }
                })
                .collect(Collectors.toList()));
    }

    private Optional<List<MailAddress>> extractAttendees(SearchRequest searchRequest) {
        return Optional.ofNullable(searchRequest.attendees)
            .filter(attendees -> !attendees.isEmpty())
            .map(attendees -> attendees.stream()
                .map(attendee -> {
                    try {
                        return new MailAddress(attendee);
                    } catch (AddressException e) {
                        throw new IllegalArgumentException("Invalid attendee email address", e);
                    }
                })
                .collect(Collectors.toList()));
    }
}
