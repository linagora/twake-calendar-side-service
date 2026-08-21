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
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLException;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.http.HttpStatus;
import org.apache.http.client.utils.URIBuilder;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Streams;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.TechnicalTokenService;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

public class CardDavClient extends DavClient {

    public static class CardDavExportException extends DavClientException {
        private final int statusCode;

        public CardDavExportException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    public enum AddressBookType {
        SYSTEM, USER;

        public static AddressBookType from(String type) {
            if (type.isEmpty()) {
                return SYSTEM;
            } else {
                return USER;
            }
        }
    }

    public record AddressBook(String value, AddressBookType type) {
    }

    public record NewAddressBook(String id, String name, String description) {
    }

    public record AddressBookSharee(@JsonProperty("dav:href") String davHref,
                                    @JsonProperty("dav:share-access") int shareAccess) {
        public AddressBookSharee {
            Preconditions.checkArgument(StringUtils.isNotBlank(davHref), "'dav:href' must not be blank");
            Preconditions.checkArgument(shareAccess >= 2 && shareAccess <= 5, "'dav:share-access' must be between 2 and 5");
        }
    }

    public static final String LIMIT_PARAM = "limit";

    private static final String SYNC_TOKEN_PROPERTY = "dav:syncToken";
    private static final Logger LOGGER = LoggerFactory.getLogger(CardDavClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String CONTENT_TYPE_VCARD = "application/vcard";
    private static final String CONTENT_TYPE_VCARD_JSON = "application/vcard+json";
    private static final String DOMAIN_MEMBERS_ADDRESS_BOOK_ID = "domain-members";
    private static final byte[] CREATE_DOMAIN_MEMBERS_ADDRESS_BOOK_PAYLOAD = """
        {
            "id": "%s",
            "dav:name": "Domain Members",
            "carddav:description": "Address book contains all domain members",
            "dav:acl": [ "{DAV:}read" ],
            "type": "group"
        }
        """.formatted(DOMAIN_MEMBERS_ADDRESS_BOOK_ID).getBytes(StandardCharsets.UTF_8);

    public CardDavClient(DavConfiguration config,
                         TechnicalTokenService technicalTokenService) throws SSLException {
        super(config, technicalTokenService);
    }

    public Mono<Void> createContact(Username username, AddressBookURL addressBookURL, ContactUid contactUid, byte[] vcardPayload) {
        HttpClient authenticatedClient = httpClientWithImpersonation(username);
        return upsertContact(authenticatedClient, addressBookURL, contactUid, vcardPayload);
    }

    public Mono<Void> upsertContact(HttpClient authenticatedClient, AddressBookURL addressBookURL, ContactUid contactUid, byte[] vcardPayload) {
        return authenticatedClient.headers(headers -> headers
                .add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_VCARD)
                .add(HttpHeaderNames.ACCEPT, HttpHeaderValues.TEXT_PLAIN))
            .put()
            .uri(addressBookURL.vcardUri(contactUid.value()).toASCIIString())
            .send(Mono.just(Unpooled.wrappedBuffer(vcardPayload)))
            .responseSingle((response, byteBufMono) -> handleContactUpsertResponse(response, byteBufMono, addressBookURL, contactUid));
    }

    public Mono<byte[]> exportContact(Username username, AddressBookURL addressBookURL) {
        HttpClient authenticatedClient = httpClientWithImpersonation(username);
        return exportContactAsVcard(authenticatedClient, addressBookURL);
    }

    private Mono<byte[]> exportContactAsVcard(HttpClient authenticatedClient, AddressBookURL addressBookURL) {
        return authenticatedClient
            .get()
            .uri(addressBookURL.asUri().toASCIIString() + "?export")
            .responseSingle((response, byteBufMono) -> {
                if (response.status().code() == HttpStatus.SC_OK) {
                    return byteBufMono.asByteArray();
                }
                return responseBodyAsString(byteBufMono)
                    .flatMap(responseBody ->
                        Mono.error(new DavClientException("""
                                Unexpected status code: %d when exporting contact for addressBookURL %s
                                %s
                                """.formatted(response.status().code(), addressBookURL, responseBody))));
            });
    }

    private Mono<Void> handleContactUpsertResponse(HttpClientResponse response, ByteBufMono responseContent, AddressBookURL addressBookURL, ContactUid contactUid) {
        return switch (response.status().code()) {
            case HttpStatus.SC_CREATED -> {
                LOGGER.debug("Create successful for contact {}", addressBookURL.vcardUri(contactUid.value()).toASCIIString());
                yield Mono.empty();
            }
            case HttpStatus.SC_NO_CONTENT -> {
                LOGGER.debug("Update successful for contact {}", addressBookURL.vcardUri(contactUid.value()).toASCIIString());
                yield Mono.empty();
            }
            default -> responseBodyAsString(responseContent)
                .flatMap(responseBody ->
                    Mono.error(new DavClientException("""
                        Unexpected status code: %d when creating contact %s
                        %s
                        """.formatted(response.status().code(), addressBookURL.vcardUri(contactUid.value()).toASCIIString(), responseBody))));
        };
    }

    public Mono<Void> createDomainMembersAddressBook(OpenPaaSId domainId) {
        return httpClientWithTechnicalToken(domainId)
            .flatMap(httpClient -> createDomainMembersAddressBook(httpClient, domainId));
    }

    private Mono<Void> createDomainMembersAddressBook(HttpClient authenticatedClient, OpenPaaSId domainId) {
        return authenticatedClient.headers(headers ->
                headers.add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                    .add(HttpHeaderNames.ACCEPT, HttpHeaderValues.APPLICATION_JSON))
            .post()
            .uri("/addressbooks/%s".formatted(domainId.value()))
            .send(Mono.fromCallable(() -> Unpooled.wrappedBuffer(CREATE_DOMAIN_MEMBERS_ADDRESS_BOOK_PAYLOAD)))
            .responseSingle((response, byteBufMono) -> {
                if (response.status().code() == 201) {
                    return Mono.empty();
                }
                return responseBodyAsString(byteBufMono)
                    .filter(serverResponse -> !Strings.CS.contains(serverResponse, "The resource you tried to create already exists"))
                    .switchIfEmpty(Mono.empty())
                    .flatMap(errorBody -> Mono.error(new DavClientException("Failed to create `domain-members` address book for domain %s: %s".formatted(domainId.value(), errorBody))));
            })
            .then();
    }

    public Mono<Void> upsertContactDomainMembers(OpenPaaSId domainId, ContactUid contactUid, byte[] vcardPayload) {
        Preconditions.checkArgument(vcardPayload != null && vcardPayload.length > 0, "vcardPayload must not be empty");

        AddressBookURL addressBookURL = new AddressBookURL(domainId, DOMAIN_MEMBERS_ADDRESS_BOOK_ID);
        return httpClientWithTechnicalToken(domainId)
            .flatMap(client -> upsertContact(client, addressBookURL, contactUid, vcardPayload)
                .onErrorResume(DavClientException.class, exception -> {
                    if (isNotFoundPathResourceError(exception)) {
                        return createDomainMembersAddressBook(domainId)
                            .then(upsertContact(client, addressBookURL, contactUid, vcardPayload))
                            .doOnSubscribe(s
                                -> LOGGER.info("Creating domain members address book for domain {} and retrying to upsert contact", domainId.value()));
                    }
                    return Mono.error(exception);
                }));
    }

    public Mono<Void> deleteContactDomainMembers(OpenPaaSId domainId, ContactUid contactUid) {
        AddressBookURL addressBookURL = new AddressBookURL(domainId, DOMAIN_MEMBERS_ADDRESS_BOOK_ID);
        return httpClientWithTechnicalToken(domainId)
            .flatMap(client -> client.headers(headers
                    -> headers.add(HttpHeaderNames.ACCEPT, HttpHeaderValues.TEXT_PLAIN))
                .delete()
                .uri(addressBookURL.vcardUri(contactUid.value()).toASCIIString())
                .responseSingle((response, byteBufMono) -> {
                    int statusCode = response.status().code();

                    if (statusCode == HttpStatus.SC_NO_CONTENT) {
                        LOGGER.debug("Delete successful for domain {} and contactUid {}", domainId.value(), contactUid.value());
                        return Mono.empty();
                    }
                    return responseBodyAsString(byteBufMono)
                        .filter(bodyStr -> !(bodyStr.contains("Card not found") && statusCode == HttpStatus.SC_NOT_FOUND))
                        .switchIfEmpty(Mono.empty())
                        .flatMap(bodyStr -> Mono.error(new DavClientException(String.format(
                            "Unexpected status code: %d when deleting contact for domain %s and contactUid: %s\n%s",
                            statusCode, domainId.value(), contactUid.value(), bodyStr))));
                }));
    }

    public Mono<byte[]> listContactDomainMembers(OpenPaaSId domainId) {
        return tryListContactDomainMembers(domainId)
            .onErrorResume(DavClientException.class, exception -> {
                if (isNotFoundPathResourceError(exception)) {
                    return createDomainMembersAddressBook(domainId)
                        .then(tryListContactDomainMembers(domainId))
                        .doOnSubscribe(s
                            -> LOGGER.info("Creating domain members address book for domain {} and retrying to list contacts", domainId.value()));
                }
                return Mono.error(exception);
            });
    }

    private boolean isNotFoundPathResourceError(DavClientException ex) {
        return Strings.CI.startsWith(ex.getMessage(), "Unexpected status code: 404")
            && (Strings.CI.contains(ex.getMessage(), "Could not find node at path: calendars/")
            || Strings.CI.contains(ex.getMessage(), "Could not find node at path: addressbooks/")
            || (Strings.CI.contains(ex.getMessage(), "Addressbook with name '")
                && Strings.CI.contains(ex.getMessage(), "' could not be found")));
    }

    private Mono<byte[]> tryListContactDomainMembers(OpenPaaSId domainId) {
        AddressBookURL url = new AddressBookURL(domainId, DOMAIN_MEMBERS_ADDRESS_BOOK_ID);
        return httpClientWithTechnicalToken(domainId)
            .flatMap(authenticatedClient -> exportContactAsVcard(authenticatedClient, url));
    }

    public Flux<AddressBook> listUserAddressBookIds(Username username, OpenPaaSId userId) {
        String uri = String.format("/addressbooks/%s?contactsCount=true&inviteStatus=2&personal=true&shared=true&subscribed=true",
            userId.value());
        return httpClientWithImpersonation(username).headers(headers -> headers
                .add(HttpHeaderNames.ACCEPT, "application/json"))
            .get()
            .uri(uri)
            .responseSingle((response, buf) -> {
                if (response.status().code() == HttpStatus.SC_OK) {
                    return buf.asString(StandardCharsets.UTF_8).map(this::extractAddressBookIdsWithType)
                        .onErrorResume(e -> Mono.error(new DavClientException(
                            "Failed to parse address book list JSON for user %s".formatted(userId.value()), e)));
                }
                return buf.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new DavClientException(
                        "Unexpected status code %d when listing address books for user %s\n%s"
                            .formatted(response.status().code(), userId.value(), errorBody))));
            }).flatMapMany(Flux::fromIterable);
    }

    private List<AddressBook> extractAddressBookIdsWithType(String json) {
        try {
            JsonNode node = JsonMapper.builder().build().readTree(json);
            ArrayNode books = (ArrayNode) node.path("_embedded").path("dav:addressbook");
            return Streams.stream(books.elements())
                .map(jsonNode -> {
                    String href = jsonNode.path("_links").path("self").path("href").asText();
                    String type = jsonNode.path("type").asText();
                    return new AddressBook(extractAddressBookId(href), AddressBookType.from(type));
                })
                .toList();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private String extractAddressBookId(String href) {
        // href: /addressbooks/{userId}/{addressBookId}.json
        String[] parts = href.split("/");
        if (parts.length == 4 && parts[1].equals("addressbooks") && parts[3].endsWith(".json")) {
            return parts[3].substring(0, parts[3].length() - 5);
        }
        throw new DavClientException("Invalid address book href: " + href);
    }

    public Mono<Void> deleteUserAddressBook(Username username, AddressBookURL addressBookURL) {
        return httpClientWithImpersonation(username).headers(headers -> headers
                .add(HttpHeaderNames.ACCEPT, CONTENT_TYPE_VCARD_JSON))
            .delete()
            .uri(addressBookURL.asUri().toASCIIString())
            .responseSingle((response, buf) -> {
                if (response.status().code() == HttpStatus.SC_NO_CONTENT) {
                    return Mono.empty();
                }
                if (response.status().code() == HttpStatus.SC_NOT_IMPLEMENTED) {
                    return Mono.error(new SystemAddressBookException(addressBookURL));
                }
                return buf.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new DavClientException(
                        "Unexpected status code: %d when deleting address book %s\n%s"
                            .formatted(response.status().code(), addressBookURL.asUri().toASCIIString(), errorBody))));
            });
    }

    public Mono<Void> deleteContact(Username username, AddressBookURL addressBookURL, ContactUid contactUid) {
        return httpClientWithImpersonation(username).headers(headers -> headers
                .add(HttpHeaderNames.ACCEPT, CONTENT_TYPE_VCARD_JSON))
            .delete()
            .uri(addressBookURL.vcardUri(contactUid.value()).toASCIIString())
            .responseSingle((response, buf) -> {
                if (response.status().code() == HttpStatus.SC_NO_CONTENT) {
                    return Mono.empty();
                }
                return buf.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new DavClientException(
                        "Unexpected status code: %d when deleting contact %s\n%s"
                            .formatted(response.status().code(),
                                addressBookURL.vcardUri(contactUid.value()).toASCIIString(),
                                errorBody))));
            });
    }

    public Mono<byte[]> listUserAddressBooksAsBytes(Username username, OpenPaaSId userId) {
        String uri = "/addressbooks/%s?contactsCount=true&inviteStatus=2&personal=true&shared=true&subscribed=true"
            .formatted(userId.value());
        return httpClientWithImpersonation(username)
            .headers(headers -> headers.add(HttpHeaderNames.ACCEPT, "application/json"))
            .get()
            .uri(uri)
            .responseSingle((response, buf) -> {
                if (response.status().code() == HttpStatus.SC_OK) {
                    return buf.asByteArray();
                }
                return buf.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new DavClientException(
                        "Unexpected status code: %d when listing address books for user %s\n%s"
                            .formatted(response.status().code(), userId.value(), errorBody))));
            });
    }

    public Mono<Boolean> addressBookExists(Username username, OpenPaaSId userId, String addressBookId) {
        String uri = new AddressBookURL(userId, addressBookId).asUri().toASCIIString();
        return httpClientWithImpersonation(username)
            .headers(headers -> headers.add(HttpHeaderNames.ACCEPT, "application/json"))
            .get()
            .uri(uri)
            .responseSingle((response, buf) -> switch (response.status().code()) {
                case HttpStatus.SC_OK -> Mono.just(true);
                case HttpStatus.SC_NOT_FOUND, HttpStatus.SC_UNAUTHORIZED, HttpStatus.SC_FORBIDDEN -> Mono.just(false);
                default -> buf.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new DavClientException(
                        "Unexpected status code: %d when checking existence of address book %s/%s\n%s"
                            .formatted(response.status().code(), userId.value(), addressBookId, errorBody))));
            });
    }

    public Mono<Void> createUserAddressBook(Username username, OpenPaaSId userId, NewAddressBook newAddressBook) {
        String description = newAddressBook.description().isBlank() ? "" : newAddressBook.description();
        byte[] payload = ("""
        {
            "id": "%s",
            "dav:name": "%s",
            "carddav:description": "%s",
            "dav:acl": ["dav:read","dav:write"],
            "type": "user"
        }
        """.formatted(newAddressBook.id(), newAddressBook.name(), description)).getBytes(StandardCharsets.UTF_8);

        return httpClientWithImpersonation(username).headers(headers -> headers
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .add(HttpHeaderNames.ACCEPT, "application/json"))
            .post()
            .uri("/addressbooks/%s".formatted(userId.value()))
            .send(Mono.fromCallable(() -> Unpooled.wrappedBuffer(payload)))
            .responseSingle((response, buf) -> {
                if (response.status().code() == HttpStatus.SC_CREATED) {
                    return Mono.empty();
                }
                return buf.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new DavClientException(
                        "Unexpected status code: %d when creating address book for user %s\n%s"
                            .formatted(response.status().code(), userId.value(), errorBody))));
            });
    }

    public Mono<Void> updateAddressBookPublicRight(Username username, AddressBookURL addressBookURL, boolean publish) {
        String uri = addressBookURL.asUri().toASCIIString();
        byte[] payload = publish
            ? "{\"dav:publish-addressbook\":{\"privilege\":\"{DAV:}read\"}}".getBytes(StandardCharsets.UTF_8)
            : "{\"dav:unpublish-addressbook\":true}".getBytes(StandardCharsets.UTF_8);

        return httpClientWithImpersonation(username)
            .headers(headers -> headers
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .add(HttpHeaderNames.ACCEPT, "application/json"))
            .post()
            .uri(uri)
            .send(Mono.fromCallable(() -> Unpooled.wrappedBuffer(payload)))
            .responseSingle((response, buf) -> switch (response.status().code()) {
                case HttpStatus.SC_OK, HttpStatus.SC_NO_CONTENT -> Mono.empty();
                default -> buf.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new DavClientException(
                        "Unexpected status code: %d when updating public right of address book %s\n%s"
                            .formatted(response.status().code(), uri, errorBody))));
            });
    }

    public Mono<Void> updateAddressBookShares(Username username, AddressBookURL addressBookURL,
                                              List<AddressBookSharee> sharees) {
        String uri = addressBookURL.asUri().toASCIIString();
        byte[] payload;
        try {
            ObjectNode body = OBJECT_MAPPER.createObjectNode()
                .set("dav:share-resource", OBJECT_MAPPER.createObjectNode()
                    .set("dav:sharee", OBJECT_MAPPER.valueToTree(sharees)));
            payload = OBJECT_MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            return Mono.error(new DavClientException("Failed to serialize address book sharing request", e));
        }

        return httpClientWithImpersonation(username)
            .headers(headers -> headers
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .add(HttpHeaderNames.ACCEPT, "application/json"))
            .post()
            .uri(uri)
            .send(Mono.fromCallable(() -> Unpooled.wrappedBuffer(payload)))
            .responseSingle((response, buf) -> switch (response.status().code()) {
                case HttpStatus.SC_OK, HttpStatus.SC_NO_CONTENT -> Mono.empty();
                default -> buf.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new DavClientException(
                        "Unexpected status code: %d when updating shares of address book %s\n%s"
                            .formatted(response.status().code(), uri, errorBody))));
            });
    }

    @VisibleForTesting
    public Mono<Void> createUserAddressBook(Username username, OpenPaaSId userId, String addressBookId, String name) {
        byte[] payload = ("""
        {
            "id": "%s",
            "dav:name": "%s",
            "dav:acl": ["dav:read","dav:write"],
            "type": "user"
        }
        """.formatted(addressBookId, name)).getBytes(StandardCharsets.UTF_8);

        return httpClientWithImpersonation(username).headers(headers -> headers
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .add(HttpHeaderNames.ACCEPT, "application/json"))
            .post()
            .uri("/addressbooks/%s".formatted(userId.value()))
            .send(Mono.fromCallable(() -> Unpooled.wrappedBuffer(payload)))
            .responseSingle((response, buf) -> {
                if (response.status().code() == HttpStatus.SC_CREATED) {
                    return Mono.empty();
                }
                return buf.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new DavClientException(
                        "Unexpected status code: %d when creating user address book for user %s\n%s"
                            .formatted(response.status().code(), userId.value(), errorBody))));
            });
    }

    private Mono<String> responseBodyAsString(ByteBufMono byteBufMono) {
        return byteBufMono.asString(StandardCharsets.UTF_8)
            .switchIfEmpty(Mono.just(StringUtils.EMPTY));
    }

    public Mono<byte[]> exportAddressBook(Username userRequest, AddressBookURL url, Map<String, String> queryParams) {
        URIBuilder uriBuilder = new URIBuilder(url.asUri());
        Optional.ofNullable(queryParams)
            .orElse(Map.of())
            .forEach(uriBuilder::addParameter);

        String uriRequest = uriBuilder.toString();

        return httpClientWithImpersonation(userRequest)
            .headers(headers -> headers.add(HttpHeaderNames.ACCEPT, CONTENT_TYPE_VCARD_JSON))
            .get()
            .uri(uriRequest)
            .responseSingle((response, byteBufMono) -> {
                if (response.status().code() == HttpStatus.SC_OK) {
                    return byteBufMono.asByteArray();
                }
                return responseBodyAsString(byteBufMono)
                    .flatMap(body -> Mono.error(new CardDavExportException(
                        "Unexpected error when exporting address book from %s\n%s"
                            .formatted(uriRequest, body), response.status().code())));
            });
    }

    public Mono<SyncToken> retrieveSyncToken(Username username, AddressBookURL addressBookURL) {
        String uri = addressBookURL.asUri().toASCIIString() + "?" + LIMIT_PARAM + "=0";

        return httpClientWithImpersonation(username)
            .headers(headers -> headers.add(HttpHeaderNames.ACCEPT, HttpHeaderValues.APPLICATION_JSON))
            .get()
            .uri(uri)
            .responseSingle((response, content) -> content.asString(StandardCharsets.UTF_8)
                .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                .flatMap(body -> switch (response.status().code()) {
                    case HttpStatus.SC_OK -> Mono.fromCallable(() -> parseSyncToken(body))
                        .flatMap(Mono::justOrEmpty)
                        .switchIfEmpty(Mono.error(() -> new DavClientException("Missing '%s' when retrieving sync token for: %s".formatted(SYNC_TOKEN_PROPERTY, uri))));
                    case HttpStatus.SC_NOT_FOUND -> Mono.error(new AddressBookNotFoundException(addressBookURL));
                    case HttpStatus.SC_FORBIDDEN -> {
                        LOGGER.debug("User {} has no rights to read address book {}", username.asString(), uri);
                        yield Mono.empty();
                    }
                    default -> Mono.error(new DavClientException("""
                        Unexpected response when retrieving sync-token for '%s'
                        Status: %d
                        Body: %s
                        """.formatted(uri, response.status().code(), body)));
                }));
    }

    private Optional<SyncToken> parseSyncToken(String body) throws JsonProcessingException {
        return Optional.ofNullable(OBJECT_MAPPER.readTree(body))
            .map(node -> node.path(SYNC_TOKEN_PROPERTY).asText(null))
            .filter(StringUtils::isNotEmpty)
            .map(SyncToken::new);
    }

}
