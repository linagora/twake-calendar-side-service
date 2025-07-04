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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import javax.net.ssl.SSLException;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;

public class DavTestHelper extends DavClient {

    public record CounterRequest(String calendarData,
                                 String sender,
                                 String recipient,
                                 String eventUid,
                                 int sequence) {
        private static final ObjectMapper mapper = new ObjectMapper();

        public String toJson() {
            ObjectNode root = mapper.createObjectNode();

            root.put("ical", calendarData);
            root.put("sender", sender);
            root.put("recipient", recipient);
            root.put("uid", eventUid);
            root.put("sequence", sequence);
            root.put("method", "COUNTER");
            return Throwing.supplier(() -> mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)).get();
        }
    }

    private final CalDavClient calDavClient;

    public DavTestHelper(DavConfiguration config) throws SSLException {
        super(config);
        this.calDavClient = new CalDavClient(config);
    }

    public void upsertCalendar(OpenPaaSUser openPaaSUser, String calendarData, String eventUid) {
        URI davCalendarUri = URI.create("/calendars/" + openPaaSUser.id().value() + "/" + openPaaSUser.id().value() + "/" + eventUid + ".ics");
        upsertCalendar(openPaaSUser.username().asString(), davCalendarUri, calendarData).block();
    }

    public void deleteCalendar(OpenPaaSUser openPaaSUser, String eventUid) {
        URI davCalendarUri = URI.create("/calendars/" + openPaaSUser.id().value() + "/" + openPaaSUser.id().value() + "/" + eventUid + ".ics");
        deleteCalendar(openPaaSUser.username().asString(), davCalendarUri).block();
    }

    public void updateCalendar(OpenPaaSUser openPaaSUser, String calendarData, String eventUid) {
        URI davCalendarUri = URI.create("/calendars/" + openPaaSUser.id().value() + "/" + openPaaSUser.id().value() + "/" + eventUid + ".ics");
        upsertCalendar(openPaaSUser.username().asString(), davCalendarUri, calendarData).block();
    }

    public Mono<Void> upsertCalendar(String username, URI uri, String calendarData) {
        return client.headers(headers -> headers.add(HttpHeaderNames.CONTENT_TYPE, "text/plain")
                .add(HttpHeaderNames.AUTHORIZATION, authenticationToken(username)))
            .request(HttpMethod.PUT)
            .uri(uri.toString())
            .send(Mono.just(Unpooled.wrappedBuffer(calendarData.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 201 || response.status().code() == 204) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(responseBody -> Mono.error(new DavClientException("""
                        Unexpected status code: %d when create/update calendar object '%s'
                        %s
                        """.formatted(response.status().code(), uri.toString(), responseBody))));
            });
    }

    public Mono<Void> postCounter(OpenPaaSUser openPaaSUser, String attendeeEventUid, CounterRequest counterRequest) {
        URI uri = URI.create("/calendars/" + openPaaSUser.id().value() + "/" + openPaaSUser.id().value() + "/" + attendeeEventUid + ".ics");
        return client.headers(headers -> headers
                .add("Content-Type", "application/calendar+json")
                .add("Accept", "application/json, text/plain, */*")
                .add("x-http-method-override", "ITIP")
                .add(HttpHeaderNames.AUTHORIZATION, authenticationToken(openPaaSUser.username().asString())))
            .request(HttpMethod.POST)
            .uri(uri.toString())
            .send(Mono.just(Unpooled.wrappedBuffer(counterRequest.toJson().getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 204) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(responseBody -> Mono.error(new DavClientException("""
                        Unexpected status code: %d when posting calendar object '%s'
                        %s
                        """.formatted(response.status().code(), uri.toString(), responseBody))));
            });
    }

    public Mono<Void> deleteCalendar(String username, URI uri) {
        return client.headers(headers -> headers.add(HttpHeaderNames.AUTHORIZATION, authenticationToken(username)))
            .request(HttpMethod.DELETE)
            .uri(uri.toString())
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 204) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(responseBody -> Mono.error(new DavClientException("""
                        Unexpected status code: %d when delete calendar object '%s'
                        %s
                        """.formatted(response.status().code(), uri.toString(), responseBody))));
            });
    }

    public Mono<String> listAddressBooks(OpenPaaSUser openPaaSUser, OpenPaaSId homeBaseId) {
        String username = openPaaSUser.username().asString();
        return client.headers(headers -> headers.add(HttpHeaderNames.AUTHORIZATION, authenticationToken(username))
                .add(HttpHeaderNames.ACCEPT, "application/json"))
            .request(HttpMethod.GET)
            .uri("/addressbooks/%s.json?personal=true&shared=true&subscribed=true".formatted(homeBaseId.value()))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 200) {
                    return responseContent.asString(StandardCharsets.UTF_8);
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(responseBody -> Mono.error(new DavClientException("""
                        Unexpected status code: %d when listing address books for user '%s', homeBaseId '%s'
                        %s
                        """.formatted(response.status().code(), homeBaseId.value(), username, responseBody))));
            });
    }

    public Optional<String> findFirstEventId(OpenPaaSUser openPaaSUser) {
        return calDavClient.findUserCalendars(openPaaSUser.username(), openPaaSUser.id())
            .flatMap(calendarURL -> calDavClient.findUserCalendarEventIds(openPaaSUser.username(), calendarURL))
            .collectList()
            .blockOptional()
            .flatMap(e -> e.stream().findFirst());
    }
}
