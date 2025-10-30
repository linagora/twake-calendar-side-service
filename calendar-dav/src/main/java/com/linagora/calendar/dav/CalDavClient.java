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
import java.util.List;
import java.util.function.Supplier;

import javax.net.ssl.SSLException;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.http.HttpStatus;
import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Streams;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.dav.dto.CalendarEventReportResponse;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.TechnicalTokenService;
import com.linagora.calendar.storage.model.ResourceId;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

public class CalDavClient extends DavClient {

    public enum PublicRight {
        READ("{DAV:}read");

        private final String value;

        PublicRight(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public record NewCalendar(@JsonProperty("id") String id,
                              @JsonProperty("dav:name") String davName,
                              @JsonProperty("apple:color") String appleColor,
                              @JsonProperty("caldav:description") String caldavDescription) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CalDavClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static class CalDavExportException extends DavClientException {
        public CalDavExportException(URI calendarUri, Username username, String davResponse) {
            super("Failed to export calendar. URL: " + calendarUri.toASCIIString() + ", User: " + username.asString() +
                "\nDav Response: " + davResponse);
        }
    }

    public static class RetriableDavClientException extends DavClientException {
        public RetriableDavClientException(String message) {
            super(message);
        }
    }

    private static final String CONTENT_TYPE_XML = "application/xml";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final HttpMethod REPORT_METHOD = HttpMethod.valueOf("REPORT");

    public CalDavClient(DavConfiguration config, TechnicalTokenService technicalTokenService) throws SSLException {
        super(config, technicalTokenService);
    }

    public Mono<byte[]> export(CalendarURL calendarURL, MailboxSession session) {
        return export(calendarURL, session.getUser());
    }

    public Mono<byte[]> export(CalendarURL calendarURL, Username username) {
        return export(username, calendarURL.asUri());
    }

    public Mono<byte[]> export(Username username, URI calendarURI) {
        return httpClientWithImpersonation(username).headers(headers ->
                headers.add(HttpHeaderNames.ACCEPT, CONTENT_TYPE_XML))
            .request(HttpMethod.GET)
            .uri(calendarURI.toString() + "?export")
            .responseSingle((response, byteBufMono) -> {
                if (response.status().code() == HttpStatus.SC_OK) {
                    return byteBufMono.asByteArray();
                } else {
                    if (response.status().code() == HttpStatus.SC_NOT_IMPLEMENTED) {
                        LOGGER.info("Could not export for {} calendar {}", username.asString(), calendarURI.toASCIIString());
                        return Mono.empty();
                    }
                    return byteBufMono
                        .asString(StandardCharsets.UTF_8)
                        .flatMap(errorBody -> Mono.error(new CalDavExportException(calendarURI, username, "Response status: " + response.status().code() + " - " + errorBody)));
                }
            });
    }

    public Mono<Void> importCalendar(CalendarURL calendarURL, String eventId, Username username, byte[] calendarData) {
        String uri = calendarURL.asUri() + "/" + eventId + ".ics" + "?import";
        return httpClientWithImpersonation(username).headers(headers ->
                headers.add(HttpHeaderNames.CONTENT_TYPE, "text/plain"))
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
        return httpClientWithImpersonation(user).headers(headers ->
                headers.add(HttpHeaderNames.ACCEPT, CONTENT_TYPE_JSON))
            .request(HttpMethod.GET)
            .uri(uri)
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == HttpStatus.SC_OK) {
                    return responseContent.asString(StandardCharsets.UTF_8).map(this::extractCalendarURLsFromResponse);
                } else {
                    return responseContent.asString(StandardCharsets.UTF_8)
                        .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                        .flatMap(errorBody -> Mono.error(new DavClientException("""
                            Unexpected status code: %d when finding user calendars for user '%s'
                            %s
                            """.formatted(response.status().code(), userId.value(), errorBody))));
                }
            }).flatMapMany(Flux::fromIterable);
    }

    public Flux<String> findUserCalendarEventIds(Username username, CalendarURL calendarURL) {
        return findUserCalendarEventIds(Mono.just(httpClientWithImpersonation(username)), calendarURL);
    }

    public Flux<String> findUserCalendarEventIds(Mono<HttpClient> httpClientPublisher, CalendarURL calendarURL) {
        return httpClientPublisher.flatMapMany(client ->
            client.headers(headers -> headers.add(HttpHeaderNames.CONTENT_TYPE, "application/xml"))
                .request(HttpMethod.valueOf("PROPFIND"))
                .uri(calendarURL.asUri().toString())
                .responseSingle((response, responseContent) -> {
                    if (response.status().code() == 207) {
                        return responseContent.asByteArray();
                    } else {
                        return responseContent.asString(StandardCharsets.UTF_8)
                            .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                            .flatMap(errorBody -> Mono.error(new DavClientException("""
                                Unexpected status code: %d when finding user calendar event ids in calendar '%s'
                                %s
                                """.formatted(response.status().code(), calendarURL.asUri(), errorBody))));
                    }
                })
                .flatMapIterable(bytes -> {
                    try {
                        return XMLUtil.extractEventIdsFromXml(bytes);
                    } catch (Exception e) {
                        throw new DavClientException("Failed to parse XML response of finding user calendar event ids in calendar " + calendarURL.asUri(), e);
                    }
                })
        );
    }

    public Mono<Void> deleteCalendarEvent(Username username, CalendarURL calendarURL, String eventId) {
        String uri = calendarURL.asUri() + "/" + eventId + ".ics";
        return httpClientWithImpersonation(username)
            .headers(headers -> headers.add(HttpHeaderNames.CONTENT_TYPE, "text/plain"))
            .request(HttpMethod.DELETE)
            .uri(uri)
            .responseSingle((response, responseContent) ->
                switch (response.status().code()) {
                    case 204 -> Mono.empty();
                    case 404 -> ReactorUtils.logAsMono(() -> LOGGER.info("Calendar object '{}' not found, nothing to delete.", uri));
                    default -> responseContent.asString(StandardCharsets.UTF_8)
                        .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                        .flatMap(responseBody -> Mono.error(new DavClientException("""
                            Unexpected status code: %d when deleting calendar object '%s'
                            %s
                            """.formatted(response.status().code(), uri, responseBody))));
            });
    }

    public Mono<Void> deleteCalendar(Username username, CalendarURL calendarURL) {
        String uri = calendarURL.asUri() + ".json";
        return httpClientWithImpersonation(username)
            .headers(headers -> headers.add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_JSON))
            .request(HttpMethod.DELETE)
            .uri(uri)
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == HttpStatus.SC_NO_CONTENT) {
                    return Mono.empty();
                } else {
                    if (response.status().code() == HttpStatus.SC_NOT_IMPLEMENTED) {
                        LOGGER.info("Could not delete user {}'s calendar {}", username.asString(), calendarURL.serialize());
                        return Mono.empty();
                    }
                    return responseContent.asString(StandardCharsets.UTF_8)
                        .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                        .flatMap(responseBody -> Mono.error(new DavClientException("""
                                Unexpected status code: %d when deleting calendar '%s'
                                %s
                                """.formatted(response.status().code(), uri, responseBody))));
                }
            });
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
    public Mono<Void> createNewCalendar(Username username, OpenPaaSId userId, NewCalendar newCalendar) {
        String uri = CalendarURL.CALENDAR_URL_PATH_PREFIX + "/" + userId.value() + ".json";
        return httpClientWithImpersonation(username)
            .headers(headers -> headers.add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_JSON)
                .add(HttpHeaderNames.ACCEPT, CONTENT_TYPE_JSON))
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

    public Mono<CalendarEventReportResponse> calendarReportByUid(Username username, OpenPaaSId calendarId, String eventUid) {
        Preconditions.checkArgument(StringUtils.isNotEmpty(eventUid), "eventUid must not be empty");
        Preconditions.checkArgument(calendarId != null, "calendarId must not be null");
        Preconditions.checkArgument(username != null, "username must not be null");

        String uri = CalendarURL.CALENDAR_URL_PATH_PREFIX + "/" + calendarId.value() + ".json";

        return httpClientWithImpersonation(username)
            .headers(headers -> headers.add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_JSON)
                .add(HttpHeaderNames.ACCEPT, CONTENT_TYPE_JSON))
            .request(REPORT_METHOD)
            .uri(uri)
            .send(Mono.fromCallable(() -> Unpooled.wrappedBuffer("""
                {"uid":"%s"}
                """.formatted(eventUid).trim().getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, content) -> {
                int statusCode = response.status().code();

                return content.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(responseAsString -> {
                        if (statusCode == HttpStatus.SC_OK) {
                            if (StringUtils.isBlank(responseAsString)) {
                                LOGGER.info("No calendar event found for user '{}' with calendarId '{}' and uid '{}'",
                                    username.asString(), calendarId.value(), eventUid);
                                return Mono.empty();
                            }
                            return Mono.fromCallable(() -> CalendarEventReportResponse.from(responseAsString));
                        }
                        if (statusCode == HttpStatus.SC_NOT_FOUND) {
                            LOGGER.info("No calendar event found for user '{}' with calendarId '{}' and uid '{}'",
                                username.asString(), calendarId.value(), eventUid);
                            return Mono.empty();
                        }

                        return Mono.error(new DavClientException("""
                            Unexpected response when get report calendar for user '%s' with calendarId %s and uid '%s',
                            Status code: %d, content body: %s"""
                            .formatted(username.asString(), calendarId.value(), eventUid, response.status().code(), responseAsString)));
                    });
            });
    }

    public Mono<Void> updateCalendarEvent(Username username, DavCalendarObject updatedCalendarObject) {
        return updateCalendarEvent(Mono.just(httpClientWithImpersonation(username)), updatedCalendarObject);
    }

    protected Mono<Void> updateCalendarEvent(Mono<HttpClient> httpClientPublisher, DavCalendarObject updatedCalendarObject) {
        return httpClientPublisher.flatMap(httpClient ->
            httpClient.headers(headers ->
                    headers.add(HttpHeaderNames.ACCEPT, CONTENT_TYPE_XML)
                        .add(HttpHeaderNames.IF_MATCH, updatedCalendarObject.eTag()))
                .request(HttpMethod.PUT)
                .uri(updatedCalendarObject.uri().toString())
                .send(Mono.just(Unpooled.wrappedBuffer(
                    updatedCalendarObject.calendarData().toString().getBytes(StandardCharsets.UTF_8))))
                .responseSingle((response, responseContent) -> {
                    HttpResponseStatus status = response.status();
                    if (status.equals(HttpResponseStatus.NO_CONTENT)) {
                        return ReactorUtils.logAsMono(() ->
                            LOGGER.info("Calendar object '{}' updated successfully.", updatedCalendarObject.uri()));
                    } else if (status.equals(HttpResponseStatus.PRECONDITION_FAILED)) {
                        return Mono.error(new RetriableDavClientException(String.format(
                            "Precondition failed (ETag mismatch) when updating calendar object '%s'. Retry may be needed.",
                            updatedCalendarObject.uri())));
                    } else {
                        return Mono.error(new DavClientException(String.format(
                            "Unexpected status code: %d when updating calendar object '%s'",
                            status.code(), updatedCalendarObject.uri())));
                    }
                })
        );
    }

    public Mono<DavCalendarObject> fetchCalendarEvent(Username username, URI calendarEventHref) {
        return fetchCalendarEvent(Mono.just(httpClientWithImpersonation(username)), calendarEventHref);
    }

    public Mono<DavCalendarObject> fetchCalendarEvent(Mono<HttpClient> httpClientPublisher, URI calendarEventHref) {
        return httpClientPublisher.flatMap(httpClient ->
            httpClient.get()
                .uri(calendarEventHref.toString())
                .responseSingle((response, content) -> {
                    int statusCode = response.status().code();

                    if (statusCode == HttpStatus.SC_OK) {
                        return content.asByteArray()
                            .flatMap(bytes -> Mono.fromCallable(() -> CalendarUtil.parseIcs(bytes)))
                            .map(ics -> new DavCalendarObject(calendarEventHref, ics, response.responseHeaders().get("ETag")));
                    }

                    if (statusCode == HttpStatus.SC_NOT_FOUND) {
                        LOGGER.info("No calendar event found for calendarHref '{}'", calendarEventHref);
                        return Mono.empty();
                    }

                    return content.asString(StandardCharsets.UTF_8)
                        .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                        .flatMap(body -> Mono.error(new DavClientException(String.format(
                            "Unexpected response when getting calendar event for calendarHref '%s'. " +
                                "Status code: %d, response body: %s",
                            calendarEventHref, statusCode, body))));
                })
        );
    }

    public Mono<JsonNode> fetchCalendarMetadata(Username username, CalendarURL calendarURL) {
        String uri = calendarURL.asUri().toString();

        return httpClientWithImpersonation(username)
            .headers(headers -> headers.add(HttpHeaderNames.ACCEPT, CONTENT_TYPE_JSON))
            .request(HttpMethod.GET)
            .uri(uri)
            .responseSingle((response, content) -> {
                int statusCode = response.status().code();

                return content.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(body -> {
                        if (statusCode == HttpStatus.SC_OK) {
                            return Mono.fromCallable(() -> OBJECT_MAPPER.readTree(body))
                                .onErrorResume(error -> Mono.error(new DavClientException(
                                    "Failed to parse calendar metadata JSON for '" + uri + "'", error)));
                        }
                        if (statusCode == HttpStatus.SC_NOT_FOUND) {
                            return Mono.error(new CalendarNotFoundException(calendarURL));
                        }
                        return Mono.error(new DavClientException("""
                                Unexpected response when fetching calendar metadata for '%s'
                                Status code: %d
                                Body: %s
                                """.formatted(uri, statusCode, body)));
                    });
            });
    }

    public Mono<Void> updateCalendarAcl(OpenPaaSUser user, PublicRight publicRight) {
        return updateCalendarAcl(user.username(), CalendarURL.from(user.id()), publicRight);
    }

    public Mono<Void> updateCalendarAcl(Username username, CalendarURL calendarURL, PublicRight publicRight) {
        String uri = calendarURL.asUri() + ".json";
        String payload = """
            {
              "public_right":"%s"
            }
            """.formatted(publicRight.getValue());

        return httpClientWithImpersonation(username).headers(headers -> headers.add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*")
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
            });
    }
    private byte[] buildPatchDelegationBodyRequest(List<Username> addOrUpdateAdmins, List<Username> revokeAdmins) {
        ObjectMapper mapper = OBJECT_MAPPER;
        ObjectNode shareNode = mapper.createObjectNode();
        ArrayNode setArray = mapper.createArrayNode();
        ArrayNode removeArray = mapper.createArrayNode();

        addOrUpdateAdmins.forEach(admin -> {
            ObjectNode adminNode = mapper.createObjectNode();
            adminNode.put("dav:href", "mailto:" + admin.asString());
            adminNode.put("dav:read-write", true);
            setArray.add(adminNode);
        });

        revokeAdmins.forEach(admin -> {
            ObjectNode adminNode = mapper.createObjectNode();
            adminNode.put("dav:href", "mailto:" + admin.asString());
            removeArray.add(adminNode);
        });

        shareNode.set("set", setArray);
        shareNode.set("remove", removeArray);
        ObjectNode bodyNode = mapper.createObjectNode();
        bodyNode.set("share", shareNode);
        try {
            return mapper.writeValueAsBytes(bodyNode);
        } catch (JsonProcessingException e) {
            throw new DavClientException("Failed to serialize JSON for patching read/write delegations", e);
        }
    }

    public Mono<Void> patchReadWriteDelegations(OpenPaaSId domainId,
                                                CalendarURL calendarURL,
                                                List<Username> addOrUpdateAdmins,
                                                List<Username> revokeAdmins) {
        if (addOrUpdateAdmins.isEmpty() && revokeAdmins.isEmpty()) {
            LOGGER.debug("No add or revoke admins found for '{}'", calendarURL);
            return Mono.empty();
        }

        return httpClientWithTechnicalToken(domainId)
            .flatMap(client -> client
                .headers(headers -> headers.add(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8")
                    .add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*"))
                .request(HttpMethod.POST)
                .uri(calendarURL.asUri() + ".json")
                .send(Mono.fromCallable(() -> Unpooled.wrappedBuffer(buildPatchDelegationBodyRequest(addOrUpdateAdmins, revokeAdmins))))
                .responseSingle((response, responseContent) -> {
                    int status = response.status().code();
                    if (status == 200 || status == 204) {
                        return Mono.empty();
                    } else {
                        return responseContent.asString(StandardCharsets.UTF_8)
                            .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                            .flatMap(body -> Mono.error(new DavClientException("Failed to patch read/write delegations. Status: " + status + ", body: " + body)));
                    }
                })
                .retryWhen(Retry.fixedDelay(1, Duration.ofMillis(500))
                        .filter(error -> Strings.CI.contains(error.getMessage(), "Could not find node at path:")))
                .then());
    }

    public Mono<Void> grantReadWriteRights(OpenPaaSId domainId, ResourceId resourceId, List<Username> administrators) {
        CalendarURL calendarURL = CalendarURL.from(resourceId.asOpenPaaSId());
        return patchReadWriteDelegations(domainId, calendarURL, administrators, List.of());
    }

    public Mono<Void> revokeWriteRights(OpenPaaSId domainId, ResourceId resourceId, List<Username> administrators) {
        CalendarURL calendarURL = CalendarURL.from(resourceId.asOpenPaaSId());
        return patchReadWriteDelegations(domainId, calendarURL, List.of(), administrators);
    }

}
