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
import java.time.Duration;
import java.util.Optional;

import javax.net.ssl.SSLException;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.james.core.Username;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.dav.dto.SubscribedCalendarRequest;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.TechnicalTokenService;
import com.linagora.calendar.storage.eventsearch.EventUid;
import com.linagora.calendar.storage.model.ResourceId;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public class DavTestHelper extends DavClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    public DavTestHelper(DavConfiguration config, TechnicalTokenService technicalTokenService) throws SSLException {
        super(config, technicalTokenService);
        this.calDavClient = new CalDavClient(config, technicalTokenService);
    }

    public void upsertCalendar(OpenPaaSUser openPaaSUser, String calendarData, EventUid eventUid) {
        upsertCalendar(openPaaSUser, calendarData, eventUid.value());
    }

    public void upsertCalendar(OpenPaaSUser openPaaSUser, String calendarData, String eventUid) {
        URI davCalendarUri = URI.create("/calendars/" + openPaaSUser.id().value() + "/" + openPaaSUser.id().value() + "/" + eventUid + ".ics");
        upsertCalendar(openPaaSUser.username(), davCalendarUri, calendarData).block();
    }

    public void deleteCalendar(OpenPaaSUser openPaaSUser, EventUid eventUid) {
        deleteCalendar(openPaaSUser, eventUid.value());
    }

    public void deleteCalendar(OpenPaaSUser openPaaSUser, String eventUid) {
        URI davCalendarUri = URI.create("/calendars/" + openPaaSUser.id().value() + "/" + openPaaSUser.id().value() + "/" + eventUid + ".ics");
        deleteCalendar(openPaaSUser.username(), davCalendarUri).block();
    }

    public void updateCalendar(OpenPaaSUser openPaaSUser, String calendarData, String eventUid) {
        URI davCalendarUri = URI.create("/calendars/" + openPaaSUser.id().value() + "/" + openPaaSUser.id().value() + "/" + eventUid + ".ics");
        upsertCalendar(openPaaSUser.username(), davCalendarUri, calendarData).block();
    }

    public Mono<Void> upsertCalendar(Username username, URI uri, String calendarData) {
        return httpClientWithImpersonation(username).headers(headers ->
                headers.add(HttpHeaderNames.CONTENT_TYPE, "text/plain"))
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
        return httpClientWithImpersonation(openPaaSUser.username()).headers(headers -> headers
                .add("Content-Type", "application/calendar+json")
                .add("Accept", "application/json, text/plain, */*")
                .add("x-http-method-override", "ITIP"))
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

    public Mono<Void> deleteCalendar(Username username, URI uri) {
        return httpClientWithImpersonation(username)
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
        return httpClientWithImpersonation(openPaaSUser.username()).headers(headers -> headers.add(HttpHeaderNames.ACCEPT, "application/json"))
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

    public Optional<String> findFirstEventId(ResourceId resourceId, OpenPaaSId domainId) {
        CalendarURL calendarURL = CalendarURL.from(resourceId.asOpenPaaSId());

        return calDavClient.findUserCalendarEventIds(httpClientWithTechnicalToken(domainId), calendarURL)
            .retryWhen(Retry.fixedDelay(2, Duration.ofMillis(500))
                .filter(throwable -> throwable instanceof DavClientException))
            .next()
            .blockOptional();
    }

    /**
     * Examples of rights:
     * - Administration (manage + admin): "dav:administration"
     * - Read/Write: "dav:read-write"
     * - Read Only: "dav:read"
     */
    public void grantDelegation(OpenPaaSUser userRequest, CalendarURL calendarURL, OpenPaaSUser delegate, String rightKey) {
        String payload = """
            {
              "share": {
                "set": [
                  {
                    "dav:href": "mailto:%s",
                    "%s": true
                  }
                ],
                "remove": []
              }
            }
            """.formatted(delegate.username().asString(), rightKey);

        sendDelegationRequest(userRequest, URI.create(calendarURL.asUri() + ".json"), payload);
    }

    public void revokeDelegation(OpenPaaSUser userRequest, CalendarURL calendarURL, OpenPaaSUser delegatedUser) {
        String payload = """
            {
                "share": {
                    "set": [],
                    "remove": [
                        {
                            "dav:href": "mailto:%s"
                        }
                    ]
                }
            }
            """.formatted(delegatedUser.username().asString());

        sendDelegationRequest(userRequest, URI.create(calendarURL.asUri() + ".json"), payload);
    }

    private void sendDelegationRequest(OpenPaaSUser userRequest, URI calendarURI, String payload) {
        httpClientWithImpersonation(userRequest.username())
            .headers(headers -> headers.add(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8")
                .add(HttpHeaderNames.ACCEPT, "application/json"))
            .request(HttpMethod.POST)
            .uri(calendarURI.toString())
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 200) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when sharing calendar '%s'
                        %s
                        """.formatted(response.status().code(), calendarURI.toASCIIString(), errorBody))));
            }).block();
    }

    public void subscribeToSharedCalendar(OpenPaaSUser user, SubscribedCalendarRequest subscribedCalendarRequest) {
        String uri = CalendarURL.CALENDAR_URL_PATH_PREFIX + "/" + user.id().value() + ".json";

        httpClientWithImpersonation(user.username())
            .headers(headers -> headers
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8")
                .add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*"))
            .request(HttpMethod.POST)
            .uri(uri)
            .send(Mono.just(Unpooled.wrappedBuffer(subscribedCalendarRequest.serialize().getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 201) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when subscribing to shared calendar '%s'
                        %s
                        """.formatted(response.status().code(), uri, errorBody))));
            }).block();
    }


    /**
     * <p>Examples of {@code public_right} values:
     * <ul>
     *     <li><b>Hide calendar</b>:
     *     <pre>{@code
     *     {"public_right": ""}
     *     }</pre>
     *     </li>
     *
     *     <li><b>See all details</b>:
     *     <pre>{@code
     *     {"public_right": "{DAV:}read"}
     *     }</pre>
     *     </li>
     *
     *     <li><b>Edit (full access)</b>:
     *     <pre>{@code
     *     {"public_right": "{DAV:}write"}
     *     }</pre>
     *     </li>
     * </ul>
     */
    public void updateCalendarAcl(OpenPaaSUser user, URI calendarURL, String publicRight) {
        String uri = calendarURL.toString();
        String payload = """
            {
              "public_right":"%s"
            }
            """.formatted(publicRight);

        httpClientWithImpersonation(user.username()).headers(headers ->
                headers.add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*")
                    .add(HttpHeaderNames.CONTENT_TYPE, "application/json"))
            .request(HttpMethod.valueOf("ACL"))
            .uri(uri)
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 200) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when updating ACL for calendar '%s'
                        %s
                        """.formatted(response.status().code(), uri, errorBody))));
            }).block();
    }

    public void updateCalendar(OpenPaaSUser user, CalendarURL calendarURL, String payload) {
        String uri = calendarURL.asUri().toASCIIString();
        httpClientWithImpersonation(user.username()).headers(headers ->
                headers.add(HttpHeaderNames.CONTENT_TYPE, "application/xml"))
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri(uri)
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 207) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when updating calendar displayname '%s'
                        %s
                        """.formatted(response.status().code(), uri, errorBody))));
            }).block();
    }

    public Mono<String> getCalendarMetadata(OpenPaaSUser openPaaSUser) {
        return getCalendarMetadata(openPaaSUser, openPaaSUser.id());
    }

    public Mono<String> getCalendarMetadata(OpenPaaSUser openPaaSUser, OpenPaaSId calendarId) {
        String uri = CalendarURL.CALENDAR_URL_PATH_PREFIX + "/" + openPaaSUser.id().value() + "/" + calendarId.value() + ".json"
            + "?personal=true&sharedDelegationStatus=accepted&sharedPublicSubscription=2&withRights=true";
        return httpClientWithImpersonation(openPaaSUser.username()).headers(headers -> headers.add(HttpHeaderNames.ACCEPT, "application/json"))
            .request(HttpMethod.GET)
            .uri(uri)
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == HttpStatus.SC_OK) {
                    return responseContent.asString(StandardCharsets.UTF_8);
                } else {
                    return responseContent.asString(StandardCharsets.UTF_8)
                        .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                        .flatMap(errorBody -> Mono.error(new RuntimeException("""
                            Unexpected status code: %d when fetching calendar metadata for user '%s'
                            %s
                            """.formatted(response.status().code(), openPaaSUser.id(), errorBody))));
                }
            });
    }

    public Mono<ArrayNode> getCalendarDelegateInvites(OpenPaaSId domainId, ResourceId resourceId) {
        return httpClientWithTechnicalToken(domainId)
            .flatMap(client ->
                client.headers(headers -> headers.add(HttpHeaderNames.ACCEPT, "application/calendar+json"))
                    .request(HttpMethod.GET)
                    .uri(CalendarURL.from(resourceId.asOpenPaaSId()).asUri().toASCIIString() + ".json?withRights=true")
                    .responseSingle((response, content) -> {
                        if (response.status().code() == 200) {
                            return content.asString(StandardCharsets.UTF_8)
                                .map(this::parseDelegateInviteArray);
                        }
                        return content.asString(StandardCharsets.UTF_8)
                            .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                            .flatMap(responseBody -> Mono.error(new DavClientException("""
                                Unexpected status code: %d when fetching calendar invites '%s'
                                %s
                                """.formatted(response.status().code(), resourceId.value(), responseBody))));
                    })
            );
    }

    private ArrayNode parseDelegateInviteArray(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode inviteNode = root.get("invite");
            if (inviteNode != null && inviteNode.isArray()) {
                return (ArrayNode) inviteNode;
            }
            return MAPPER.createArrayNode();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse invite array from response", e);
        }
    }

    public Mono<String> fetchEventsBySyncToken(OpenPaaSUser user, CalendarURL calendarURL, String syncToken) {
        String body = """
            {
                "sync-token": "{syncToken}"
            }
            """.replace("{syncToken}", syncToken);

        return httpClientWithImpersonation(user.username())
            .headers(headers -> headers
                .add(HttpHeaderNames.ACCEPT, "application/json")
                .add("Depth", "0"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri(calendarURL.asUri() + ".json")
            .send(Mono.just(Unpooled.wrappedBuffer(body.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, content) -> {
                if (response.status().code() == 207) {
                    return content.asString(StandardCharsets.UTF_8);
                }
                return content.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(error -> Mono.error(new DavClientException("""
                        Unexpected status code: %d when fetching events by sync-token '%s'
                        %s
                        """.formatted(response.status().code(), syncToken, error))));
            });
    }
}
