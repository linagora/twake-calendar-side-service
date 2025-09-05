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
import java.time.Duration;
import java.util.List;

import javax.net.ssl.SSLException;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.http.HttpStatus;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Streams;
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
import reactor.util.retry.Retry;

public class CardDavClient extends DavClient {

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

    static class RetryableDavClientException extends RuntimeException {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CardDavClient.class);

    private static final String CONTENT_TYPE_VCARD = "application/vcard";
    private static final String ACCEPT_VCARD_JSON = "text/plain";
    private static final String ADDRESS_BOOK_PATH = "/addressbooks/%s/%s/%s.vcf";

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

    public Mono<Void> createContact(Username username, OpenPaaSId userId, String addressBook, String vcardUid, byte[] vcardPayload) {
        HttpClient authenticatedClient = httpClientWithImpersonation(username);
        return upsertContact(authenticatedClient, userId, addressBook, vcardUid, vcardPayload);
    }

    public Mono<Void> upsertContact(HttpClient authenticatedClient, OpenPaaSId homeBaseId, String addressBook, String vcardUid, byte[] vcardPayload) {
        return authenticatedClient.headers(headers -> headers
                .add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_VCARD)
                .add(HttpHeaderNames.ACCEPT, ACCEPT_VCARD_JSON))
            .put()
            .uri(String.format(ADDRESS_BOOK_PATH, homeBaseId.value(), addressBook, vcardUid))
            .send(Mono.just(Unpooled.wrappedBuffer(vcardPayload)))
            .responseSingle((response, byteBufMono) -> handleContactUpsertResponse(response, byteBufMono, homeBaseId, addressBook, vcardUid));
    }

    public Mono<byte[]> exportContact(Username username, OpenPaaSId userId, String addressBook) {
        HttpClient authenticatedClient = httpClientWithImpersonation(username);
        return exportContactAsVcard(authenticatedClient, userId, addressBook);
    }

    private Mono<byte[]> exportContactAsVcard(HttpClient authenticatedClient, OpenPaaSId homeBaseId, String addressBook) {
        return authenticatedClient
            .get()
            .uri("/addressbooks/%s/%s?export".formatted(homeBaseId.value(), addressBook))
            .responseSingle((response, byteBufMono) -> {
                if (response.status().code() == 200) {
                    return byteBufMono.asByteArray();
                }
                return responseBodyAsString(byteBufMono)
                    .flatMap(responseBody ->
                        Mono.error(new DavClientException("""
                                Unexpected status code: %d when exporting contact for homeBaseId %s and addressBook %s
                                %s
                                """.formatted(response.status().code(), homeBaseId.value(), addressBook, responseBody))));
            });
    }

    private Mono<Void> handleContactUpsertResponse(HttpClientResponse response, ByteBufMono responseContent, OpenPaaSId homeBaseId, String addressBook, String vcardUid) {
        return switch (response.status().code()) {
            case 201 -> {
                LOGGER.debug("Create successful for homeBaseId {} and addressBook {} and vcardUid {}", homeBaseId.value(), addressBook, vcardUid);
                yield Mono.empty();
            }
            case 204 -> {
                LOGGER.debug("Update successful for homeBaseId {} and addressBook {} and vcardUid {}", homeBaseId.value(), addressBook, vcardUid);
                yield Mono.empty();
            }
            default -> responseBodyAsString(responseContent)
                .flatMap(responseBody ->
                    Mono.error(new DavClientException("""
                                Unexpected status code: %d when creating contact for homeBaseId %s and addressBook %s and vcardUid: %s
                                %s
                                """.formatted(response.status().code(), homeBaseId.value(), addressBook, vcardUid, responseBody))));
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
            .uri("/addressbooks/%s.json".formatted(domainId.value()))
            .send(Mono.fromCallable(() -> Unpooled.wrappedBuffer(CREATE_DOMAIN_MEMBERS_ADDRESS_BOOK_PAYLOAD)))
            .responseSingle((res, buf) -> handleCreateAddressBookResponse(res, buf, domainId))
            .retryWhen(Retry.fixedDelay(1, Duration.ofMillis(500))
                .filter(throwable -> throwable instanceof RetryableDavClientException))
            .then();
    }

    private Mono<Void> handleCreateAddressBookResponse(HttpClientResponse response, ByteBufMono byteBufMono, OpenPaaSId domainId) {
        return switch (response.status().code()) {
            case 201 -> Mono.empty();
            case 404 ->
                // The first request to esn-sabre may fail if the request user's calendar has not been lazy-provisioned yet
                // https://github.com/linagora/esn-sabre/blob/master/lib/CalDAV/Backend/Esn.php#L41
                Mono.error(new RetryableDavClientException());
            default -> responseBodyAsString(byteBufMono)
                .filter(serverResponse -> !Strings.CS.contains(serverResponse, "The resource you tried to create already exists"))
                .switchIfEmpty(Mono.empty())
                .flatMap(errorBody -> Mono.error(new DavClientException(
                    "Failed to create `domain-members` address book for domain %s: %s".formatted(domainId.value(), errorBody))));
        };
    }

    public Mono<Void> upsertContactDomainMembers(OpenPaaSId domainId, String vcardUid, byte[] vcardPayload) {
        Preconditions.checkArgument(StringUtils.isNotEmpty(vcardUid), "vcardUid must not be empty");
        Preconditions.checkArgument(vcardPayload != null && vcardPayload.length > 0, "vcardPayload must not be empty");

        return httpClientWithTechnicalToken(domainId)
            .flatMap(client
                -> upsertContact(client, domainId, DOMAIN_MEMBERS_ADDRESS_BOOK_ID, vcardUid, vcardPayload));
    }

    public Mono<Void> deleteContactDomainMembers(OpenPaaSId domainId, String vcardUid) {
        Preconditions.checkArgument(StringUtils.isNotEmpty(vcardUid), "vcardUid must not be empty");

        return httpClientWithTechnicalToken(domainId)
            .flatMap(client -> client.headers(headers
                    -> headers.add(HttpHeaderNames.ACCEPT, ACCEPT_VCARD_JSON))
                .delete()
                .uri(String.format(ADDRESS_BOOK_PATH, domainId.value(), DOMAIN_MEMBERS_ADDRESS_BOOK_ID, vcardUid))
                .responseSingle((response, byteBufMono) -> {
                    int statusCode = response.status().code();

                    if (statusCode == 204) {
                        LOGGER.debug("Delete successful for domain {} and vcardUid {}", domainId.value(), vcardUid);
                        return Mono.empty();
                    }
                    return responseBodyAsString(byteBufMono)
                        .filter(bodyStr -> !(bodyStr.contains("Card not found") && statusCode == 404))
                        .switchIfEmpty(Mono.empty())
                        .flatMap(bodyStr -> Mono.error(new DavClientException(String.format(
                            "Unexpected status code: %d when deleting contact for domain %s and vcardUid: %s\n%s",
                            statusCode, domainId.value(), vcardUid, bodyStr))));
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
            || Strings.CI.contains(ex.getMessage(), "Could not find node at path: addressbooks/"));
    }

    private Mono<byte[]> tryListContactDomainMembers(OpenPaaSId domainId) {
        return httpClientWithTechnicalToken(domainId)
            .flatMap(authenticatedClient -> exportContactAsVcard(authenticatedClient, domainId, DOMAIN_MEMBERS_ADDRESS_BOOK_ID));
    }

    public Flux<AddressBook> listUserAddressBookIds(Username username, OpenPaaSId userId) {
        String uri = String.format("/addressbooks/%s.json?contactsCount=true&inviteStatus=2&personal=true&shared=true&subscribed=true",
            userId.value());
        return httpClientWithImpersonation(username).headers(headers -> headers
                .add(HttpHeaderNames.ACCEPT, "application/json"))
            .get()
            .uri(uri)
            .responseSingle((response, buf) -> {
                if (response.status().code() == 200) {
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

    public Mono<Void> deleteUserAddressBook(Username username, OpenPaaSId userId, String addressBookId) {
        String uri = String.format("/addressbooks/%s/%s.json", userId.value(), addressBookId);
        return httpClientWithImpersonation(username).headers(headers -> headers
                .add(HttpHeaderNames.ACCEPT, "application/vcard+json"))
            .delete()
            .uri(uri)
            .responseSingle((response, buf) -> {
                if (response.status().code() == HttpStatus.SC_NO_CONTENT) {
                    return Mono.empty();
                }
                if (response.status().code() == HttpStatus.SC_NOT_IMPLEMENTED) {
                    LOGGER.info("Could not delete address book {} of user {}", addressBookId, userId.value());
                    return Mono.empty();
                }
                return buf.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new DavClientException(
                        "Unexpected status code: %d when deleting address book %s for user %s\n%s"
                            .formatted(response.status().code(), addressBookId, userId.value(), errorBody))));
            });
    }

    public Mono<Void> deleteContact(Username username, OpenPaaSId userId, String addressBookId, String vcardUid) {
        String uri = String.format("/addressbooks/%s/%s/%s.vcf", userId.value(), addressBookId, vcardUid);
        return httpClientWithImpersonation(username).headers(headers -> headers
                .add(HttpHeaderNames.ACCEPT, "application/vcard+json"))
            .delete()
            .uri(uri)
            .responseSingle((response, buf) -> {
                if (response.status().code() == 204) {
                    return Mono.empty();
                }
                return buf.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new DavClientException(
                        "Unexpected status code: %d when deleting contact %s in address book %s for user %s\n%s"
                            .formatted(response.status().code(), vcardUid, addressBookId, userId.value(), errorBody))));
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
            .uri("/addressbooks/%s.json".formatted(userId.value()))
            .send(Mono.fromCallable(() -> Unpooled.wrappedBuffer(payload)))
            .responseSingle((response, buf) -> {
                if (response.status().code() == 201) {
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
}
