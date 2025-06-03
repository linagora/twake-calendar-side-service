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

package com.linagora.calendar.dav;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.UnaryOperator;

import javax.net.ssl.SSLException;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Streams;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class CalDavClient extends DavClient {

    public record NewCalendar(@JsonProperty("id") String id,
                              @JsonProperty("dav:name") String davName,
                              @JsonProperty("apple:color") String appleColor,
                              @JsonProperty("caldav:description") String caldavDescription) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CalDavClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static class CalDavExportException extends DavClientException {
        public CalDavExportException(CalendarURL calendarUrl, Username username, String davResponse) {
            super("Failed to export calendar. URL: " + calendarUrl.asUri() + ", User: " + username.asString() +
                "\nDav Response: " + davResponse);
        }
    }

    private static final String CONTENT_TYPE_XML = "application/xml";
    private static final String CONTENT_TYPE_JSON = "application/json";

    public CalDavClient(DavConfiguration config) throws SSLException {
        super(config);
    }

    private UnaryOperator<HttpHeaders> addHeaders(String username) {
        return headers -> headers.add(HttpHeaderNames.ACCEPT, CONTENT_TYPE_XML)
            .add(HttpHeaderNames.AUTHORIZATION, authenticationToken(username));
    }

    public Mono<byte[]> export(CalendarURL calendarURL, MailboxSession session) {
        return export(calendarURL, session.getUser());
    }

    public Mono<byte[]> export(CalendarURL calendarURL, Username username) {
        return client.headers(headers -> addHeaders(username.asString()).apply(headers))
            .request(HttpMethod.GET)
            .uri(calendarURL.asUri() + "?export")
            .responseSingle((response, byteBufMono) -> {
                if (response.status().code() == HttpStatus.SC_OK) {
                    return byteBufMono.asByteArray();
                } else {
                    return byteBufMono
                        .asString(StandardCharsets.UTF_8)
                        .flatMap(errorBody -> Mono.error(new CalDavExportException(calendarURL, username, "Response status: " + response.status().code() + " - " + errorBody)));
                }
            });
    }

    public Mono<Void> importCalendar(CalendarURL calendarURL, String eventId, Username username, byte[] calendarData) {
        String uri = calendarURL.asUri() + "/" + eventId + ".ics" + "?import";
        return client.headers(headers -> headers.add(HttpHeaderNames.CONTENT_TYPE, "text/plain")
                .add(HttpHeaderNames.AUTHORIZATION, authenticationToken(username.asString())))
            .request(HttpMethod.PUT)
            .uri(uri)
            .send(Mono.just(Unpooled.wrappedBuffer(calendarData)))
            .responseSingle((response, responseContent) -> {
                switch (response.status().code()) {
                    case 201:
                        return ReactorUtils.logAsMono(() -> LOGGER.info("Calendar object '{}' created successfully.", uri));
                    default:
                        return responseContent.asString(StandardCharsets.UTF_8)
                            .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                            .flatMap(responseBody -> Mono.error(new DavClientException("""
                                Unexpected status code: %d when create calendar object '%s'
                                %s
                                """.formatted(response.status().code(), uri.toString(), responseBody))));

                }
            });
    }

    public Flux<CalendarURL> findUserCalendars(Username user, OpenPaaSId userId) {
        String uri = CalendarURL.CALENDAR_URL_PATH_PREFIX + "/" + userId.value() + ".json"
            + "?personal=true&sharedDelegationStatus=accepted&sharedPublicSubscription=true&withRights=true";
        return client.headers(headers -> headers
                .add(HttpHeaderNames.ACCEPT, CONTENT_TYPE_JSON)
                .add(HttpHeaderNames.AUTHORIZATION, authenticationToken(user.asString())))
            .request(HttpMethod.GET)
            .uri(uri)
            .responseSingle((response, byteBufMono) -> {
                if (response.status().code() == HttpStatus.SC_OK) {
                    return byteBufMono.asString(StandardCharsets.UTF_8).map(this::extractCalendarURLsFromResponse);
                } else {
                    return Mono.error(new DavClientException(
                        String.format("Unexpected status code: %d when finding user calendars for user: %s",
                            response.status().code(), userId.value())));
                }
            }).flatMapMany(Flux::fromIterable);
    }

    private List<CalendarURL> extractCalendarURLsFromResponse(String json) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            ArrayNode calendars = (ArrayNode) node.path("_embedded").path("dav:calendar");
            return Streams.stream(calendars.elements())
                .map(calendarNode -> calendarNode.path("_links").path("self").path("href").asText())
                .filter(href -> !href.isEmpty())
                .map(this::parseCalendarHref)
                .toList();
        } catch (Exception e) {
            throw new DavClientException("Failed to parse calendar list JSON", e);
        }
    }

    private CalendarURL parseCalendarHref(String href) {
        String[] parts = href.split("/");
        if (parts.length != 4) {
            throw new DavClientException("Found an invalid calendar href in JSON response: " + href);
        }
        String userId = parts[2];
        String calendarIdWithExt = parts[3];
        String calendarId = calendarIdWithExt.replace(".json", "");
        return new CalendarURL(new OpenPaaSId(userId), new OpenPaaSId(calendarId));
    }

    @VisibleForTesting
    public Mono<Void> createNewCalendarDirectory(Username username, OpenPaaSId userId, NewCalendar newCalendar) {
        String uri = CalendarURL.CALENDAR_URL_PATH_PREFIX + "/" + userId.value() + ".json";
        return client.headers(headers -> headers.add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_JSON)
                .add(HttpHeaderNames.ACCEPT, CONTENT_TYPE_JSON)
                .add(HttpHeaderNames.AUTHORIZATION, authenticationToken(username.asString())))
            .request(HttpMethod.POST)
            .uri(uri)
            .send(Mono.fromCallable(() -> Unpooled.wrappedBuffer(OBJECT_MAPPER.writeValueAsBytes(newCalendar))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == HttpStatus.SC_CREATED) {
                    return Mono.empty();
                } else {
                    return responseContent.asString(StandardCharsets.UTF_8)
                        .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                        .flatMap(responseBody -> Mono.error(new DavClientException("""
                                Unexpected status code: %d when create new calendar directory '%s' in '%s'
                                %s
                                """.formatted(response.status().code(), newCalendar.id(), uri, responseBody))));
                }
            });
    }
}
